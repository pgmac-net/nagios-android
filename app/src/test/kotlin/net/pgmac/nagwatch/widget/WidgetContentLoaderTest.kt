// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.notify.draftWith
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.PollAttempt
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.criticalService
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A widget is drawn from what is on disk, by a process that may never have seen the poll. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class WidgetContentLoaderTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val statusDatabase = inMemoryStatusDatabase()
    private val cache = StatusCache(statusDatabase.cache())
    private val clock = MutableClock(T0)
    private val bindings = FakeWidgetBindings()
    private val loader = WidgetContentLoader(bindings, profiles, cache, clock)

    @After
    fun close() {
        database.close()
        statusDatabase.close()
    }

    @Test
    fun `a widget with no profile chosen has none, and says so`() = runBlocking {
        val view = loader.load(41)

        assertNull(view.profileId)
        assertEquals(WidgetState.PROFILE_GONE, view.content.state)
    }

    @Test
    fun `a widget whose profile was deleted has none either`() = runBlocking {
        val id = profiles.save(draftWith(NotificationSettings()))
        bindings.bind(41, id)
        profiles.delete(id)

        val view = loader.load(41)

        assertNull(view.profileId)
        assertEquals(WidgetState.PROFILE_GONE, view.content.state)
    }

    @Test
    fun `it shows its own profile's last poll, read from the cache`() = runBlocking {
        val home = profiles.save(draftWith(NotificationSettings()))
        val office = profiles.save(draftWith(NotificationSettings(), name = "Office"))
        bindings.bind(41, home)
        bindings.bind(42, office)
        cache.savePoll(home, snapshot(services = listOf(criticalService())))
        cache.saveAttempt(home, PollAttempt(T0, null))
        clock.advance(Duration.ofMinutes(5))

        val view = loader.load(41)

        assertEquals(home, view.profileId)
        assertEquals("Home", view.content.profileName)
        assertEquals(WidgetState.CURRENT, view.content.state)
        assertEquals(1, view.content.counts?.critical)
        assertEquals("for durations beside each problem", T0 + Duration.ofMinutes(5), view.now)
        assertEquals("the other widget has nothing to show yet", WidgetState.NO_DATA, loader.load(42).content.state)
    }

    @Test
    fun `a failed attempt on disk makes it unreachable, and time alone makes it stale`() = runBlocking {
        val id = profiles.save(draftWith(NotificationSettings()))
        bindings.bind(41, id)
        cache.savePoll(id, snapshot())
        cache.saveAttempt(id, PollAttempt(T0, null))

        clock.advance(Duration.ofMinutes(31))
        assertEquals(WidgetState.STALE, loader.load(41).content.state)

        cache.saveAttempt(id, PollAttempt(clock.instant(), OutageCause.UNREACHABLE))
        assertEquals(WidgetState.UNREACHABLE, loader.load(41).content.state)
    }
}
