// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.AnnouncedStore
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.criticalService
import net.pgmac.nagwatch.status.failure
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One background run: who is polled, and what is remembered afterwards. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class BackgroundPollerTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val statusDatabase = inMemoryStatusDatabase()
    private val client = FakeClient()
    private val announced = AnnouncedStore(statusDatabase.cache())
    private val scheduler = FakePollScheduler()
    private val statuses = statuses(profiles)

    /** What was remembered at the moment each thing was said. */
    private val rememberedWhenSaid = mutableListOf<AnnouncedState>()
    private val notifier = FakeNotifier { profile -> rememberedWhenSaid += runBlocking { announced.load(profile.id) } }
    private val poller = BackgroundPoller(profiles, statuses, announced, scheduler, notifier)

    @After
    fun close() {
        database.close()
        statusDatabase.close()
    }

    @Test
    fun `a profile that is gone is not polled, and its job is cancelled`() = runBlocking {
        assertEquals(PollRun.NotWanted, poller.poll(42))

        assertEquals(0, client.fetches + client.connects)
        assertEquals(listOf("cancel 42"), scheduler.events.toList())
        assertTrue(notifier.delivered.isEmpty())
    }

    @Test
    fun `a profile with notifications off is not polled, and its job is cancelled`() = runBlocking {
        val id = profiles.save(draftWith(NotificationSettings(enabled = false)))

        assertEquals(PollRun.NotWanted, poller.poll(id))

        assertEquals("no request the person did not ask for", 0, client.fetches + client.connects)
        assertEquals(listOf("cancel $id"), scheduler.events.toList())
        assertEquals(AnnouncedState(), announced.load(id))
        assertTrue(notifier.delivered.isEmpty())
    }

    @Test
    fun `what a run decides is said, for that profile, before it is remembered as said`() = runBlocking {
        val id = profiles.save(draftWith())
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }

        val run = poller.poll(id) as PollRun.Decided

        assertEquals(listOf(id to run.decision), notifier.delivered)
        // If the app died between the two, saying it twice is harmless and never saying it is not.
        assertEquals(listOf(AnnouncedState()), rememberedWhenSaid)
        assertTrue(announced.load(id).initialised)
    }

    @Test
    fun `a run with nothing to say still hands over its decision, so anything stale is taken down`() = runBlocking {
        val id = profiles.save(draftWith())
        poller.poll(id)

        poller.poll(id)

        assertEquals(2, notifier.delivered.size)
        assertTrue(notifier.delivered.last().second.post.isEmpty())
    }

    @Test
    fun `the first poll is summarised, remembered, and shown to an open app`() = runBlocking {
        val id = profiles.save(draftWith())
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }

        val run = poller.poll(id)

        assertEquals(listOf<Notice>(Notice.Summary(count = 1, first = true)), run.posted())
        assertTrue(announced.load(id).initialised)
        assertEquals("the same fetch the screens use", 1, statuses.statuses.value[id]?.report?.counts?.critical)
        assertTrue(scheduler.events.isEmpty())
    }

    @Test
    fun `what one run remembered decides the next`() = runBlocking {
        val id = profiles.save(draftWith())
        poller.poll(id)

        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        val second = poller.poll(id)
        val third = poller.poll(id)

        assertEquals(Notice.Change.NEW, (second.posted().single() as Notice.Raised).change)
        assertTrue("already said", third.posted().isEmpty())
    }

    @Test
    fun `failed polls are counted across runs, and the third is worth saying`() = runBlocking {
        val id = profiles.save(draftWith())
        poller.poll(id)
        client.statusResult = { failure() }

        val first = poller.poll(id)
        val second = poller.poll(id)
        val third = poller.poll(id)

        assertTrue(first.posted().isEmpty() && second.posted().isEmpty())
        assertEquals(OutageCause.SERVER_ERROR, (third.posted().single() as Notice.Outage).cause)
        assertEquals(Announcer.FAILURES_BEFORE_TELLING, announced.load(id).failures)
    }

    @Test
    fun `the profile's own choices are the ones applied`() = runBlocking {
        val all = profiles.save(draftWith(NotificationSettings(enabled = true, unhandledOnly = false)))
        val quiet = profiles.save(
            draftWith(NotificationSettings(enabled = true, tellUnreachable = false), name = "LAN"),
        )
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(acknowledged()))) }

        assertEquals(
            "with only-unhandled off, an acknowledged problem counts",
            listOf<Notice>(Notice.Summary(count = 1, first = true)),
            poller.poll(all).posted(),
        )

        client.statusResult = { failure() }
        val runs = List(Announcer.FAILURES_BEFORE_TELLING + 1) { poller.poll(quiet) }
        assertTrue("asked not to be told", runs.all { it.posted().isEmpty() })
    }

    @Test
    fun `credentials that can no longer be read are worth saying at the first run`() = runBlocking {
        val id = profiles.save(draftWith())
        // The same database after a device restore: the profile is there, the key is not.
        val restored = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
        val afterRestore = BackgroundPoller(restored, statuses(restored), announced, scheduler, notifier)

        val run = afterRestore.poll(id)

        val outage = run.posted().single() as Notice.Outage
        assertEquals(OutageCause.CREDENTIALS_UNREADABLE, outage.cause)
        assertEquals(StatusError.CredentialsUnavailable, outage.error)
        assertEquals(0, client.fetches + client.connects)
    }

    @Test
    fun `a run that meets the app already refreshing does nothing, and remembers nothing`() = runBlocking {
        val id = profiles.save(draftWith())
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.statusResult = {
            fetching.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            NagiosResult.Success(snapshot(services = listOf(criticalService())))
        }
        val app = thread { runBlocking { statuses.refresh(id) } }
        assertTrue(fetching.await(WAIT_SECONDS, TimeUnit.SECONDS))

        val run = poller.poll(id)
        release.countDown()
        app.join()

        assertEquals(PollRun.AlreadyRunning, run)
        assertTrue(notifier.delivered.isEmpty())
        assertEquals("so the next run is still the first", AnnouncedState(), announced.load(id))
        assertNotNull(statuses.statuses.value[id]?.report)
    }

    private fun statuses(profiles: ProfileRepository) =
        StatusRepository(profiles, provider(client), StatusCache(statusDatabase.cache()), MutableClock(T0))

    private fun PollRun.posted(): List<Notice> = (this as PollRun.Decided).decision.post

    private fun acknowledged() = ServiceStatus(
        hostName = "web01",
        description = "Disk /",
        state = ServiceState.CRITICAL,
        check = CheckStatus(acknowledged = true),
    )

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
