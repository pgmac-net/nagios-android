// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.cache.DetailCache
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a detail screen is told, in order, as an object is loaded. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DetailRepositoryTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val statusDatabase = inMemoryStatusDatabase()
    private val clock = MutableClock(T0)
    private val cache = DetailCache(statusDatabase.cache(), clock)
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val repository = DetailRepository(profiles, provider(client), cache, clock)

    private val web01 = HostStatus("web01", HostState.UP, CheckStatus(pluginOutput = "PING OK"))
    private val disk = ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(pluginOutput = "97% used"))
    private val hostRef = ObjectRef("web01")
    private val diskRef = ObjectRef("web01", "Disk /")
    private val calls = mutableListOf<String>()

    @After
    fun close() {
        profileDatabase.close()
        statusDatabase.close()
    }

    @Test
    fun `a first visit starts empty, says it is loading, then fills in piece by piece`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve(comments = listOf(comment(1)), downtimes = listOf(downtime(7)))

        val seen = repository.open(id, diskRef).toList()

        assertNull("nothing saved yet", seen.first().record)
        assertFalse(seen.first().refreshing)
        assertTrue(
            "then it says it is loading",
            seen[1].refreshing && seen[1].comments.loading && seen[1].downtimes.loading,
        )
        val done = seen.last()
        assertEquals(disk, (done.record as CachedDetail.Service).status)
        assertEquals(listOf(comment(1)), done.comments.items)
        assertEquals(listOf(downtime(7)), done.downtimes.items)
        assertFalse(done.refreshing || done.comments.loading || done.downtimes.loading)
        assertNull(done.error)
        assertEquals(
            "the record is shown before its comments have arrived",
            disk,
            seen[2].record?.let {
                (it as CachedDetail.Service).status
            },
        )
        assertTrue(seen[2].comments.loading)
        assertEquals(listOf("service:Disk /", "comments", "downtimes"), calls)
    }

    @Test
    fun `a host is fetched as a host`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve()

        val done = repository.open(id, hostRef).toList().last()

        assertEquals(web01, (done.record as CachedDetail.Host).status)
        assertEquals("host:web01", calls.first())
    }

    @Test
    fun `a second visit shows what was saved at once, before anything is fetched`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve(comments = listOf(comment(1)), downtimes = listOf(downtime(7)))
        repository.open(id, diskRef).toList()
        calls.clear()

        val first = repository.open(id, diskRef).toList().first()

        assertEquals(disk, (first.record as CachedDetail.Service).status)
        assertEquals(listOf(comment(1)), first.comments.items)
        assertEquals(listOf(downtime(7)), first.downtimes.items)
        assertEquals(T0, first.comments.fetchedAt)
    }

    @Test
    fun `offline, what was saved stays on screen with the reason beside it, and Nagios is asked only once`() =
        runBlocking {
            val id = profiles.saveTestProfile()
            serve(comments = listOf(comment(1)))
            repository.open(id, diskRef).toList()
            calls.clear()
            val unreachable = NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "timed out")
            client.serviceResult = { ref ->
                calls += "service:${ref.description}"
                failure(unreachable)
            }

            val done = repository.open(id, diskRef).toList().last()

            assertEquals(disk, (done.record as CachedDetail.Service).status)
            assertEquals(StatusError.Nagios(unreachable), done.error)
            assertEquals("saved comments are still there", listOf(comment(1)), done.comments.items)
            assertEquals(StatusError.Nagios(unreachable), done.comments.error)
            assertFalse(done.refreshing || done.comments.loading || done.downtimes.loading)
            assertEquals("no point waiting for two more timeouts", listOf("service:Disk /"), calls)
        }

    @Test
    fun `comments failing leaves the record and the downtimes alone`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve(downtimes = listOf(downtime(7)))
        client.commentsResult = { failure(NagiosError.Forbidden) }

        val done = repository.open(id, diskRef).toList().last()

        assertNull(done.error)
        assertEquals(disk, (done.record as CachedDetail.Service).status)
        assertEquals(StatusError.Nagios(NagiosError.Forbidden), done.comments.error)
        assertNull(done.downtimes.error)
        assertEquals(listOf(downtime(7)), done.downtimes.items)
    }

    @Test
    fun `when only the comments fail, the ones saved on an earlier visit stay, with the reason`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve(comments = listOf(comment(1)), downtimes = listOf(downtime(7)))
        repository.open(id, diskRef).toList()
        client.commentsResult = { failure(NagiosError.Forbidden) }
        client.downtimesResult = { failure(NagiosError.Forbidden) }

        val done = repository.open(id, diskRef).toList().last()

        assertNull("the record itself refreshed", done.error)
        assertEquals(listOf(comment(1)), done.comments.items)
        assertEquals(T0, done.comments.fetchedAt)
        assertEquals(StatusError.Nagios(NagiosError.Forbidden), done.comments.error)
        assertEquals(listOf(downtime(7)), done.downtimes.items)
        assertEquals(StatusError.Nagios(NagiosError.Forbidden), done.downtimes.error)
        assertFalse(done.comments.loading || done.downtimes.loading)
    }

    @Test
    fun `downtimes failing leaves the record and the comments alone`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve(comments = listOf(comment(1)))
        client.downtimesResult = { failure(NagiosError.Api(8, "error", "no such query")) }

        val done = repository.open(id, diskRef).toList().last()

        assertNull(done.error)
        assertNull(done.comments.error)
        assertEquals(listOf(comment(1)), done.comments.items)
        assertEquals(StatusError.Nagios(NagiosError.Api(8, "error", "no such query")), done.downtimes.error)
    }

    @Test
    fun `a record Nagios cannot serve still gets its comments, which are shown but not kept`() = runBlocking {
        // The record whose output crashes the CGI: a server error for it, while its comments load.
        val id = profiles.saveTestProfile()
        serve(comments = listOf(comment(1)))
        client.serviceResult = { failure(NagiosError.Http(500)) }

        val done = repository.open(id, diskRef).toList().last()

        assertNull(done.record)
        assertEquals(StatusError.Nagios(NagiosError.Http(500)), done.error)
        assertEquals(listOf(comment(1)), done.comments.items)
        assertNull(done.comments.error)
        assertTrue("nothing to hang them on in the cache", cache.loadComments(id, diskRef).items.isEmpty())
    }

    @Test
    fun `what is fetched is saved for next time, comments trimmed to what the cache keeps`() = runBlocking {
        val id = profiles.saveTestProfile()
        val many = (1L..30L).map { comment(it, at = T0.plusSeconds(it)) }.reversed()
        serve(comments = many, downtimes = listOf(downtime(7)))

        val done = repository.open(id, diskRef).toList().last()

        assertEquals("all of them are on screen", 30, done.comments.items.size)
        assertEquals(disk, (cache.load(id, diskRef) as CachedDetail.Service).status)
        assertEquals(DetailCache.MAX_ANNOTATIONS, cache.loadComments(id, diskRef).items.size)
        assertEquals("the newest are the ones kept", 30L, cache.loadComments(id, diskRef).items.first().id)
        assertEquals(listOf(downtime(7)), cache.loadDowntimes(id, diskRef).items)
    }

    @Test
    fun `a refresh starts from what is on screen rather than flashing back to the cache`() = runBlocking {
        val id = profiles.saveTestProfile()
        val many = (1L..30L).map { comment(it, at = T0.plusSeconds(it)) }.reversed()
        serve(comments = many)
        val shown = repository.open(id, diskRef).toList().last()

        val first = repository.open(id, diskRef, from = shown).toList().first()

        assertEquals("still all thirty, not the twenty the cache holds", 30, first.comments.items.size)
        assertTrue(first.refreshing)
    }

    @Test
    fun `a profile that no longer exists is reported, and nothing is fetched`() = runBlocking {
        serve()

        val done = repository.open(profileId = 999, ref = diskRef).toList().last()

        assertEquals(StatusError.ProfileMissing, done.error)
        assertEquals(StatusError.ProfileMissing, done.comments.error)
        assertFalse(done.refreshing)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the link to Nagios is offered once the CGI directory is known`() = runBlocking {
        val id = profiles.saveTestProfile()
        serve()
        assertNull(repository.open(id, diskRef).toList().last().nagiosUrl)

        profiles.rememberCgiBase(id, CGI)

        assertEquals(
            "https://nagios.example.org/nagios/cgi-bin/extinfo.cgi?type=2&host=web01&service=Disk%20%2F",
            repository.open(id, diskRef).toList().last().nagiosUrl,
        )
    }

    @Test
    fun `the link names a host or a service, with awkward names encoded`() {
        assertEquals(
            "https://nagios.example.org/nagios/cgi-bin/extinfo.cgi?type=1&host=web01",
            DetailRepository.nagiosUrl(CGI, hostRef),
        )
        val awkward = DetailRepository.nagiosUrl(CGI, ObjectRef("a b", "50% & more?#x"))
        assertEquals(
            "https://nagios.example.org/nagios/cgi-bin/extinfo.cgi?type=2&host=a%20b&service=50%25%20%26%20more%3F%23x",
            awkward,
        )
        assertNull(DetailRepository.nagiosUrl(null, hostRef))
    }

    @Test
    fun `the link never carries credentials, even if the address had some`() {
        val withSecrets = "https://user:hunter2@nagios.example.org/nagios/cgi-bin/".toHttpUrl()

        val url = checkNotNull(DetailRepository.nagiosUrl(withSecrets, hostRef))

        assertFalse(url, url.contains("hunter2") || url.contains("user") || url.contains("@"))
    }

    private fun serve(
        comments: List<net.pgmac.nagwatch.nagios.model.Comment> = emptyList(),
        downtimes: List<net.pgmac.nagwatch.nagios.model.Downtime> = emptyList(),
    ) {
        client.hostResult = { name ->
            calls += "host:$name"
            NagiosResult.Success(web01)
        }
        client.serviceResult = { ref ->
            calls += "service:${ref.description}"
            NagiosResult.Success(disk)
        }
        client.commentsResult = {
            calls += "comments"
            NagiosResult.Success(comments)
        }
        client.downtimesResult = {
            calls += "downtimes"
            NagiosResult.Success(downtimes)
        }
    }
}
