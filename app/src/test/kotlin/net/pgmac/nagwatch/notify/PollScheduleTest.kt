// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.cache.AnnouncedStore
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A profile is polled in the background exactly while its notifications are on. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class PollScheduleTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val scheduler = FakePollScheduler()
    private val statusDatabase = inMemoryStatusDatabase()
    private val announced = AnnouncedStore(statusDatabase.cache())
    private val notifier = FakeNotifier()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val schedule = PollSchedule(profiles, scheduler, notifier, announced, scope)

    /** Something announced, so that forgetting it can be seen. */
    private val told = AnnouncedState(
        initialised = true,
        known = mapOf(ObjectRef("web01", "Disk /") to Known(Severity.CRITICAL, individually = true)),
    )

    private val off = NotificationSettings(enabled = false)
    private val on = NotificationSettings(enabled = true)

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        statusDatabase.close()
    }

    @Test
    fun `a profile with notifications off is not polled, and anything left from before is taken off`() {
        val id = save(off)

        schedule.start()

        assertEquals(listOf("cancel $id"), events(1))
    }

    @Test
    fun `a profile with notifications on is polled at its interval`() {
        val id = save(on.copy(interval = PollInterval.THIRTY_MINUTES))

        schedule.start()

        assertEquals(listOf("schedule $id 30"), events(1))
    }

    @Test
    fun `switching notifications on schedules the poll, and off cancels it`() {
        val id = save(off)
        schedule.start()
        events(1)

        save(on, id)
        assertEquals("schedule $id 15", events(2).last())

        save(off, id)
        assertEquals("cancel $id", events(3).last())
    }

    @Test
    fun `changing the interval reschedules`() {
        val id = save(on)
        schedule.start()
        events(1)

        save(on.copy(interval = PollInterval.ONE_HOUR), id)

        assertEquals("schedule $id 60", events(2).last())
    }

    @Test
    fun `a change that is not about polling leaves the schedule alone`() {
        val id = save(on)
        schedule.start()
        events(1)

        runBlocking { profiles.save(draftWith(on.copy(unhandledOnly = false), id, name = "Renamed")) }
        pause()

        assertEquals(listOf("schedule $id 15"), scheduler.events.toList())
        assertTrue("nothing showing is taken down for a profile still being watched", notifier.cleared.isEmpty())
    }

    @Test
    fun `switching notifications off takes down what the profile has showing, and forgets what was announced`() {
        val id = save(on)
        runBlocking { announced.save(id, told) }
        schedule.start()
        events(1)
        until { notifier.swept.isNotEmpty() }
        assertEquals("watched, so the first look leaves it alone", told, runBlocking { announced.load(id) })

        save(off, id)

        until { runBlocking { announced.load(id) } == AnnouncedState() }
        assertEquals(listOf(id), notifier.cleared.toList())
        assertEquals("cancel $id", events(2).last())
    }

    @Test
    fun `switching them back on is a first poll, not a continuation`() {
        val id = save(on)
        runBlocking { announced.save(id, told) }
        schedule.start()
        events(1)
        save(off, id)
        until { runBlocking { announced.load(id) } == AnnouncedState() }

        save(on, id)

        assertEquals("schedule $id 15", events(3).last())
        assertFalse(runBlocking { announced.load(id) }.initialised)
    }

    @Test
    fun `deleting a profile takes down what it has showing`() {
        val id = save(on)
        runBlocking { announced.save(id, told) }
        schedule.start()
        events(1)

        runBlocking { profiles.delete(id) }

        until { notifier.cleared.isNotEmpty() }
        assertEquals(listOf(id), notifier.cleared.toList())
        until { runBlocking { announced.load(id) } == AnnouncedState() }
    }

    @Test
    fun `one profile's change takes nobody else's notifications down`() {
        val home = save(on)
        val office = save(on, name = "Office")
        runBlocking {
            announced.save(home, told)
            announced.save(office, told)
        }
        schedule.start()
        events(2)

        save(off, office)

        until { notifier.cleared.isNotEmpty() }
        assertEquals(listOf(office), notifier.cleared.toList())
        until { runBlocking { announced.load(office) } == AnnouncedState() }
        assertEquals(told, runBlocking { announced.load(home) })
    }

    @Test
    fun `an edit to a profile that is off clears nothing`() {
        val id = save(off)
        schedule.start()
        events(1)
        until { notifier.swept.isNotEmpty() }

        runBlocking { profiles.save(draftWith(off, id, name = "Renamed")) }
        pause()

        assertTrue(notifier.cleared.isEmpty())
    }

    @Test
    fun `on the first look it sweeps up everything but what is being watched`() {
        val watched = save(on)
        val unwatched = save(off, name = "Office")
        val alsoWatched = save(on, name = "Lab")

        schedule.start()

        until { notifier.swept.isNotEmpty() }
        assertEquals(listOf(setOf(watched, alsoWatched)), notifier.swept.toList())
        assertTrue("and does not mistake the first look for a change", notifier.cleared.isEmpty())
        assertTrue(unwatched !in notifier.swept.single())
    }

    @Test
    fun `on the first look what was announced is forgotten for a profile that is off, and kept for one that is on`() {
        val off1 = save(off)
        val on1 = save(on, name = "Office")
        runBlocking {
            announced.save(off1, told)
            announced.save(on1, told)
        }

        schedule.start()

        until { runBlocking { announced.load(off1) } == AnnouncedState() }
        until { notifier.swept.isNotEmpty() }
        assertEquals(told, runBlocking { announced.load(on1) })
    }

    @Test
    fun `it sweeps once per start, not at every change`() {
        val id = save(on)
        schedule.start()
        events(1)
        until { notifier.swept.isNotEmpty() }

        save(on.copy(interval = PollInterval.ONE_HOUR), id)
        events(2)
        pause()

        assertEquals(1, notifier.swept.size)
    }

    @Test
    fun `deleting a profile cancels its poll`() {
        val id = save(on)
        schedule.start()
        events(1)

        runBlocking { profiles.delete(id) }

        assertEquals("cancel $id", events(2).last())
    }

    @Test
    fun `one profile's change does not touch another's schedule`() {
        val home = save(on)
        val office = save(on, name = "Office")
        schedule.start()
        events(2)

        save(off, office)

        assertEquals(listOf("schedule $home 15", "schedule $office 15", "cancel $office"), events(3))
    }

    private fun save(settings: NotificationSettings, id: Long? = null, name: String = "Home"): Long =
        runBlocking { profiles.save(draftWith(settings, id, name)) }

    /** The schedule follows the database from another thread, so wait for it to catch up. */
    private fun events(count: Int): List<String> = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (scheduler.events.size < count) delay(POLL_MS)
        }
        scheduler.events.toList()
    }

    /** The schedule works from another thread, so wait for what it was asked to do. */
    private fun until(condition: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    /** For the case that is about something not happening: give it a moment to happen. */
    private fun pause() = runBlocking { delay(NOTHING_HAPPENS_MS) }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val NOTHING_HAPPENS_MS = 300L
    }
}
