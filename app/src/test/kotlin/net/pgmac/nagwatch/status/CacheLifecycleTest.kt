// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.profile.DataStoreSelectedProfile
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput.Keep
import net.pgmac.nagwatch.profile.SecretInput.Replace
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.cache.CacheJanitor
import net.pgmac.nagwatch.status.cache.StatusCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How the cache behaves over the app's life: served first on a cold start,
 * cleared with its profile, swept for orphans, and the chosen profile remembered.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class CacheLifecycleTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val statusDatabase = inMemoryStatusDatabase()
    private val clock = MutableClock(T0)
    private val cache = StatusCache(statusDatabase.cache())
    private val profiles =
        ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json, setOf(cache))
    private val client = FakeClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        scope.cancel()
        profileDatabase.close()
        statusDatabase.close()
    }

    @Test
    fun `a refresh writes the cache, and a later run of the app shows it before any network`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        repository().refresh(id)

        // A new process: nothing in memory, and the server must not be asked.
        val offline = FakeClient(statusResult = { failure() })
        val restarted = repository(offline)
        clock.advance(Duration.ofHours(3))
        restarted.showCached(id)

        val status = checkNotNull(restarted.statuses.value[id])
        assertEquals(1, status.report?.counts?.critical)
        assertEquals("it knows how old that is", T0, status.lastSuccess)
        assertTrue("three hours old is stale, and says so", status.isStale(clock.instant()))
        assertEquals("nothing was fetched to show it", 0, offline.fetches)
    }

    @Test
    fun `the lists of every host and service survive a restart too, not only the problems`() = runBlocking {
        val id = saveProfile()
        val states = listOf(
            ServiceStateEntry("web01", "Ping", ServiceState.OK),
            ServiceStateEntry("web01", "Disk /", ServiceState.CRITICAL),
        )
        client.statusResult = {
            NagiosResult.Success(snapshot(services = listOf(criticalService())).copy(serviceStates = states))
        }
        val first = repository()
        first.refresh(id)
        assertEquals("held from the fetch", states, first.statuses.value[id]?.snapshot?.serviceStates)

        val restarted = repository(FakeClient(statusResult = { failure() }))
        restarted.showCached(id)

        val snapshot = checkNotNull(restarted.statuses.value[id]?.snapshot)
        assertEquals(states.toSet(), snapshot.serviceStates.toSet())
        assertEquals(listOf("web01"), snapshot.hosts.map { it.name })
    }

    @Test
    fun `with no cache there is simply nothing to show yet`() = runBlocking {
        val id = saveProfile()
        val repository = repository()

        repository.showCached(id)

        assertNull(repository.statuses.value[id])
    }

    @Test
    fun `a failed refresh on a cold start keeps the cached list and adds the error`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        repository().refresh(id)

        val restarted = repository(FakeClient(statusResult = { failure() }))
        restarted.refreshIfOlderThan(id, Duration.ZERO)

        val status = checkNotNull(restarted.statuses.value[id])
        assertEquals("cached list still shown", 1, status.report?.counts?.critical)
        assertTrue(status.error is StatusError.Nagios)
    }

    @Test
    fun `showing the cache never overwrites something newer`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        val repository = repository()
        repository.refresh(id)
        client.statusResult = { NagiosResult.Success(snapshot(at = T0 + Duration.ofMinutes(5))) }
        clock.advance(Duration.ofMinutes(5))
        repository.refresh(id)

        repository.showCached(id)

        assertEquals("the fresh, empty result stays", 0, repository.statuses.value[id]?.report?.counts?.total)
    }

    @Test
    fun `refresh if old shows the cache first, and skips the fetch when the cache is recent`() = runBlocking {
        val id = saveProfile()
        repository().refresh(id)
        val nagios = FakeClient()
        val restarted = repository(nagios)
        clock.advance(Duration.ofSeconds(20))

        restarted.refreshIfOlderThan(id, Duration.ofSeconds(60))

        assertTrue("the cached result is on screen", restarted.statuses.value[id]?.report != null)
        assertEquals("twenty seconds old is recent enough: no fetch", 0, nagios.fetches)
    }

    @Test
    fun `deleting a profile removes its cached status`() = runBlocking {
        val id = saveProfile()
        val other = saveProfile("Other")
        repository().refresh(id)
        repository().refresh(other)

        profiles.delete(id)

        assertNull(cache.loadPoll(id))
        assertTrue("another profile's cache is untouched", cache.loadPoll(other) != null)
    }

    @Test
    fun `the startup sweep removes cache left behind by a profile that no longer exists`() = runBlocking {
        val id = saveProfile()
        repository().refresh(id)
        // What a crash between the two deletions leaves: the profile row gone, the cache not.
        profileDatabase.profiles().delete(id)
        assertTrue("orphaned", cache.loadPoll(id) != null)

        CacheJanitor(profiles, cache, scope).sweep()

        assertNull(cache.loadPoll(id))
    }

    @Test
    fun `the sweep leaves the cache of existing profiles alone`() = runBlocking {
        val id = saveProfile()
        repository().refresh(id)

        CacheJanitor(profiles, cache, scope).sweep()

        assertTrue(cache.loadPoll(id) != null)
    }

    @Test
    fun `the chosen profile is remembered on disk across restarts`() = runBlocking {
        val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = DataStoreSelectedProfile(PreferenceDataStoreFactory.create(scope = firstRun) { file })
        assertNull("nothing chosen on a fresh install", before.id.first())

        before.select(42)
        assertEquals(42L, before.id.first())
        firstRun.cancel()
        firstRun.coroutineContext[Job]?.join()

        val after = DataStoreSelectedProfile(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals("a new process reads the same choice", 42L, after.id.first())
        assertFalse(file.readBytes().isEmpty())
    }

    private fun repository(nagios: FakeClient = client) = StatusRepository(profiles, provider(nagios), cache, clock)

    private suspend fun saveProfile(name: String = "Home"): Long = profiles.save(
        ProfileDraft(
            id = null,
            name = name,
            baseUrl = "https://nagios.example.org/nagios",
            username = "nagwatch",
            password = Replace("pw"),
            accessClientId = "",
            accessClientSecret = Keep,
            customHeaders = emptyList(),
            allowCleartext = false,
        ),
    )
}
