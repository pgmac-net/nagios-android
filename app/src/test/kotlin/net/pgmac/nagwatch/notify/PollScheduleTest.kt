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
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val schedule = PollSchedule(profiles, scheduler, scope)

    private val off = NotificationSettings(enabled = false)
    private val on = NotificationSettings(enabled = true)

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
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

    /** For the case that is about something not happening: give it a moment to happen. */
    private fun pause() = runBlocking { delay(NOTHING_HAPPENS_MS) }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val NOTHING_HAPPENS_MS = 300L
    }
}
