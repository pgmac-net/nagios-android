// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.profile.HeaderDraft
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput.Keep
import net.pgmac.nagwatch.profile.SecretInput.Replace
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class StatusRepositoryTest {
    private val database = inMemoryProfileDatabase()
    private val keys = softwareKeySource()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(keys), Json)
    private val clock = MutableClock(T0)
    private val client = FakeClient()
    private val repository = StatusRepository(profiles, provider(client), clock)

    @After
    fun close() = database.close()

    @Test
    fun `a refresh stores the classified report and when it was fetched`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }

        repository.refresh(id)

        val status = checkNotNull(repository.statuses.value[id])
        assertEquals(1, status.report?.counts?.critical)
        assertEquals(T0, status.lastSuccess)
        assertNull(status.error)
        assertFalse(status.refreshing)
    }

    @Test
    fun `a failed refresh keeps the last good report and records the error`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        repository.refresh(id)
        val good = checkNotNull(repository.statuses.value[id]?.report)

        client.statusResult = { failure(NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "x")) }
        repository.refresh(id)

        val status = checkNotNull(repository.statuses.value[id])
        assertSame("a network error must not blank what was on screen", good, status.report)
        assertTrue(status.error is StatusError.Nagios)
        assertEquals("lastSuccess is still the good one", T0, status.lastSuccess)
        assertFalse(status.refreshing)
    }

    @Test
    fun `the next success clears the error`() = runBlocking {
        val id = saveProfile()
        client.statusResult = { failure() }
        repository.refresh(id)
        assertNotNull(repository.statuses.value[id]?.error)

        client.statusResult = { NagiosResult.Success(snapshot()) }
        repository.refresh(id)

        assertNull(repository.statuses.value[id]?.error)
    }

    @Test
    fun `a first failure leaves no report to fall back on`() = runBlocking {
        val id = saveProfile()
        client.connectResult = failure(NagiosError.BadCredentials)

        repository.refresh(id)

        val status = checkNotNull(repository.statuses.value[id])
        assertNull(status.report)
        assertEquals(StatusError.Nagios(NagiosError.BadCredentials), status.error)
    }

    @Test
    fun `the first poll finds the CGI directory and remembers it, later polls skip the search`() = runBlocking {
        val id = saveProfile()

        repository.refresh(id)
        assertEquals(1, client.connects)
        repository.refresh(id)

        assertEquals("connect only while the directory is unknown", 1, client.connects)
        assertEquals(2, client.fetches)
        assertEquals(CGI, (profiles.settingsFor(id) as SettingsResult.Ready).settings.cgiBase)
    }

    @Test
    fun `credentials that cannot be decrypted are reported as such and nothing is sent`() = runBlocking {
        val id = saveProfile()
        var created = 0
        val afterRestore = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
        val repo = StatusRepository(afterRestore, provider(client) { created++ }, clock)

        repo.refresh(id)

        assertEquals(StatusError.CredentialsUnavailable, repo.statuses.value[id]?.error)
        assertEquals("no client may be made without usable credentials", 0, created)
    }

    @Test
    fun `a profile that no longer exists is reported, not thrown`() = runBlocking {
        repository.refresh(999)

        assertEquals(StatusError.ProfileMissing, repository.statuses.value[999]?.error)
    }

    @Test
    fun `an invalid stored URL is reported`() = runBlocking {
        val id = saveProfile(baseUrl = "not a url")

        repository.refresh(id)

        assertEquals(StatusError.InvalidUrl, repository.statuses.value[id]?.error)
    }

    @Test
    fun `overlapping refreshes of one profile collapse into one fetch`() = runBlocking {
        val id = saveProfile()
        val gate = CompletableDeferred<Unit>()
        val counting = FakeClient()
        val gated = StatusRepository(profiles, { gatedOn(gate, counting) }, clock)

        val first = async { gated.refresh(id) }
        yield()
        assertTrue("the first refresh is in flight", gated.statuses.value[id]?.refreshing == true)
        gated.refresh(id)
        gate.complete(Unit)
        first.await()

        assertEquals("the second call did nothing", 1, counting.fetches)
    }

    @Test
    fun `refresh if old fetches only when nothing is held or it is older than the limit`() = runBlocking {
        val id = saveProfile()

        repository.refreshIfOlderThan(id, Duration.ofSeconds(60))
        assertEquals("nothing held yet", 1, client.fetches)

        clock.advance(Duration.ofSeconds(30))
        repository.refreshIfOlderThan(id, Duration.ofSeconds(60))
        assertEquals("fresh enough", 1, client.fetches)

        clock.advance(Duration.ofSeconds(31))
        repository.refreshIfOlderThan(id, Duration.ofSeconds(60))
        assertEquals("now old", 2, client.fetches)
    }

    @Test
    fun `a cancelled refresh does not leave the profile stuck refreshing`() = runBlocking {
        val id = saveProfile()
        val repo = StatusRepository(profiles, { gatedOn(CompletableDeferred(), client) }, clock)

        val job = async { repo.refresh(id) }
        yield()
        assertTrue(repo.statuses.value[id]?.refreshing == true)
        job.cancel()
        runCatching { job.await() }

        assertFalse(repo.statuses.value[id]?.refreshing == true)
        StatusRepository(profiles, provider(client), clock).refresh(id)
        assertEquals("the profile can be refreshed again", 1, client.fetches)
    }

    @Test
    fun `status is stale only after twice the poll interval`() {
        val status = ProfileStatus(lastSuccess = T0)

        assertFalse(status.isStale(T0 + Duration.ofMinutes(29)))
        assertTrue(status.isStale(T0 + Duration.ofMinutes(31)))
        assertFalse("never fetched is not stale, it is empty", ProfileStatus().isStale(T0 + Duration.ofDays(2)))
    }

    private suspend fun saveProfile(baseUrl: String = "https://nagios.example.org/nagios"): Long = profiles.save(
        ProfileDraft(
            id = null,
            name = "Home",
            baseUrl = baseUrl,
            username = "nagwatch",
            password = Replace("pw"),
            accessClientId = "",
            accessClientSecret = Keep,
            customHeaders = emptyList<HeaderDraft>(),
            allowCleartext = false,
        ),
    )

    /** A client whose fetch waits on [gate], so a test can hold a refresh open. */
    private fun gatedOn(gate: CompletableDeferred<Unit>, inner: FakeClient) = object : NagiosClient by inner {
        override suspend fun fetchStatus(): NagiosResult<StatusSnapshot> {
            gate.await()
            return inner.fetchStatus()
        }
    }
}
