// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.notify.AnnouncedState
import net.pgmac.nagwatch.notify.Known
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What was announced has to outlast the process: the next poll may be hours away, in a new one. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class AnnouncedStoreTest {
    private val database = inMemoryStatusDatabase()
    private val dao = database.cache()
    private val store = AnnouncedStore(dao)

    private val state = AnnouncedState(
        initialised = true,
        known = mapOf(
            ObjectRef("web01", "Disk /") to Known(Severity.CRITICAL, individually = true),
            ObjectRef("web01", "Load") to Known(Severity.WARNING, individually = false),
            // A host, and a service with a name that is all punctuation.
            ObjectRef("db01") to Known(Severity.HOST_DOWN, individually = true),
            ObjectRef("db01", "a 'b' \"c\" % ;") to Known(Severity.UNKNOWN, individually = false),
        ),
        failures = 2,
        outage = OutageCause.CREDENTIALS_REJECTED,
    )

    @After
    fun close() = database.close()

    @Test
    fun `a profile that was never polled starts with nothing said and its first poll still to come`() = runBlocking {
        assertEquals(AnnouncedState(), store.load(HOME))
    }

    @Test
    fun `what was announced comes back exactly, hosts and services alike`() = runBlocking {
        store.save(HOME, state)

        assertEquals(state, store.load(HOME))
    }

    @Test
    fun `a first poll that found nothing wrong is still remembered as done`() = runBlocking {
        store.save(HOME, AnnouncedState(initialised = true))

        assertEquals(AnnouncedState(initialised = true), store.load(HOME))
    }

    @Test
    fun `failures are remembered before any poll has succeeded`() = runBlocking {
        val failing = AnnouncedState(initialised = false, failures = 3, outage = OutageCause.UNREACHABLE)
        store.save(HOME, failing)

        assertEquals(failing, store.load(HOME))
    }

    @Test
    fun `each save replaces the last, so what recovered is forgotten`() = runBlocking {
        store.save(HOME, state)
        val later = AnnouncedState(
            initialised = true,
            known = mapOf(ObjectRef("web01", "Load") to Known(Severity.CRITICAL, individually = true)),
        )

        store.save(HOME, later)

        assertEquals(later, store.load(HOME))
    }

    @Test
    fun `profiles are kept apart`() = runBlocking {
        val office =
            AnnouncedState(initialised = true, known = mapOf(ObjectRef("gw") to Known(Severity.HOST_DOWN, true)))
        store.save(HOME, state)
        store.save(OFFICE, office)

        assertEquals(state, store.load(HOME))
        assertEquals(office, store.load(OFFICE))
    }

    @Test
    fun `deleting a profile forgets what was announced for it, and only for it`() = runBlocking {
        store.save(HOME, state)
        store.save(OFFICE, state)

        StatusCache(dao).onProfileDeleted(HOME)

        assertEquals(AnnouncedState(), store.load(HOME))
        assertTrue("no rows left behind for an id that may be used again", dao.announced(HOME).isEmpty())
        assertEquals(state, store.load(OFFICE))
    }

    @Test
    fun `the sweep for profiles that no longer exist takes their memory too`() = runBlocking {
        store.save(HOME, state)
        store.save(OFFICE, state)

        StatusCache(dao).sweepOrphans(listOf(OFFICE))
        assertEquals(AnnouncedState(), store.load(HOME))
        assertTrue(dao.announced(HOME).isEmpty())
        assertEquals(state, store.load(OFFICE))

        StatusCache(dao).sweepOrphans(emptyList())
        assertEquals(AnnouncedState(), store.load(OFFICE))
    }

    @Test
    fun `a name written by another version of the app is dropped, not a crash`() = runBlocking {
        store.save(HOME, state)
        val raw = database.openHelper.writableDatabase
        raw.execSQL("UPDATE announced SET severity = 'CATASTROPHIC' WHERE description = 'Load'")
        raw.execSQL("UPDATE announce_meta SET outage = 'SOLAR_FLARE'")

        val loaded = store.load(HOME)

        assertEquals(state.known - ObjectRef("web01", "Load"), loaded.known)
        assertEquals(null, loaded.outage)
        assertEquals(state.failures, loaded.failures)
    }

    private companion object {
        const val HOME = 1L
        const val OFFICE = 2L
    }
}
