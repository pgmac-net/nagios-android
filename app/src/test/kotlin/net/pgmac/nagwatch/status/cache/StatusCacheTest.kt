// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class StatusCacheTest {
    private val database = inMemoryStatusDatabase()
    private val dao = database.cache()
    private val clock = MutableClock(T0)
    private val cache = StatusCache(dao)
    private val details = DetailCache(dao, clock)

    @After
    fun close() = database.close()

    @Test
    fun `nothing is cached for a profile that was never polled`() = runBlocking {
        assertNull(cache.loadPoll(PROFILE))
    }

    @Test
    fun `a poll survives a round trip through the cache`() = runBlocking {
        val full = CheckStatus(
            stateType = StateType.SOFT,
            pluginOutput = "CRITICAL - value 97",
            longOutput = "line two",
            perfData = "value=97;80;90;0;100",
            currentAttempt = 2,
            maxAttempts = 3,
            lastCheck = T0 - Duration.ofMinutes(1),
            nextCheck = T0 + Duration.ofMinutes(4),
            lastStateChange = T0 - Duration.ofHours(2),
            acknowledged = true,
            downtimeDepth = 1,
            checksEnabled = false,
            notificationsEnabled = false,
            activeCheck = false,
            flapping = true,
        )
        val snapshot = poll(
            // In name order, which is how the cache returns them.
            hosts = listOf(HostStatus("db01", HostState.DOWN, CheckStatus()), HostStatus("web01", HostState.UP, full)),
            problems = listOf(ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, full)),
            states = listOf(state("web01", "Disk /", ServiceState.CRITICAL), state("web01", "PING", ServiceState.OK)),
        )

        cache.savePoll(PROFILE, snapshot)

        assertEquals(snapshot, cache.loadPoll(PROFILE))
    }

    @Test
    fun `a degraded record stays degraded`() = runBlocking {
        val degraded = ServiceStatus("web01", "Odd", ServiceState.CRITICAL, CheckStatus(detailsAvailable = false))

        cache.savePoll(PROFILE, poll(problems = listOf(degraded)))

        assertFalse(checkNotNull(cache.loadPoll(PROFILE)).serviceProblems.single().check.detailsAvailable)
    }

    @Test
    fun `each poll replaces the last one wholesale`() = runBlocking {
        cache.savePoll(
            PROFILE,
            poll(
                problems = listOf(service("Disk /"), service("Load")),
                states = listOf(
                    state("web01", "Disk /", ServiceState.CRITICAL),
                    state("web01", "Gone", ServiceState.OK),
                ),
            ),
        )

        clock.advance(Duration.ofMinutes(5))
        cache.savePoll(
            PROFILE,
            poll(
                at = clock.instant(),
                problems = listOf(service("Load")),
                states = listOf(state("web01", "Load", ServiceState.CRITICAL)),
            ),
        )

        val loaded = checkNotNull(cache.loadPoll(PROFILE))
        assertEquals(listOf("Load"), loaded.serviceProblems.map { it.description })
        assertEquals(
            "a service removed from Nagios must not linger",
            listOf("Load"),
            loaded.serviceStates.map {
                it.description
            },
        )
        assertEquals(T0 + Duration.ofMinutes(5), loaded.fetchedAt)
    }

    @Test
    fun `profiles are kept apart`() = runBlocking {
        cache.savePoll(PROFILE, poll(problems = listOf(service("Disk /"))))
        cache.savePoll(OTHER, poll(problems = listOf(service("Load"))))

        assertEquals(listOf("Disk /"), cache.loadPoll(PROFILE)?.serviceProblems?.map { it.description })
        assertEquals(listOf("Load"), cache.loadPoll(OTHER)?.serviceProblems?.map { it.description })
    }

    @Test
    fun `an opened record is kept, with when it was fetched, and is not part of the poll`() = runBlocking {
        cache.savePoll(PROFILE, poll())
        clock.advance(Duration.ofMinutes(3))

        details.saveOpened(PROFILE, service("PING", ServiceState.OK))

        val detail = details.load(PROFILE, ObjectRef("web01", "PING")) as CachedDetail.Service
        assertEquals(ServiceState.OK, detail.status.state)
        assertEquals(T0 + Duration.ofMinutes(3), detail.fetchedAt)
        assertTrue(
            "an opened OK service is not a problem in the poll",
            checkNotNull(cache.loadPoll(PROFILE)).serviceProblems.isEmpty(),
        )
    }

    @Test
    fun `an opened record survives later polls that do not include it`() = runBlocking {
        cache.savePoll(PROFILE, poll())
        details.saveOpened(PROFILE, service("PING", ServiceState.OK))

        cache.savePoll(PROFILE, poll(at = T0 + Duration.ofMinutes(15)))

        assertTrue(details.load(PROFILE, ObjectRef("web01", "PING")) is CachedDetail.Service)
    }

    @Test
    fun `a poll refreshes an opened record and it stays opened`() = runBlocking {
        details.saveOpened(PROFILE, service("Disk /", ServiceState.OK))
        details.saveComments(PROFILE, ObjectRef("web01", "Disk /"), listOf(comment(1)))

        cache.savePoll(PROFILE, poll(at = T0 + Duration.ofMinutes(15), problems = listOf(service("Disk /"))))

        val detail = details.load(PROFILE, ObjectRef("web01", "Disk /")) as CachedDetail.Service
        assertEquals("the newer state from the poll", ServiceState.CRITICAL, detail.status.state)
        assertEquals(
            "its comments are still there",
            1,
            details.loadComments(PROFILE, ObjectRef("web01", "Disk /")).items.size,
        )
        assertEquals(1, dao.openedCount(PROFILE))
    }

    @Test
    fun `only the most recently opened records are kept`() = runBlocking {
        repeat(5) { index ->
            clock.advance(Duration.ofSeconds(1))
            dao.saveOpened(row("svc$index"), openedAt = clock.millis(), keep = 3)
        }

        assertEquals(3, dao.openedCount(PROFILE))
        assertNull("the oldest is gone", details.load(PROFILE, ObjectRef("web01", "svc0")))
        assertNull(details.load(PROFILE, ObjectRef("web01", "svc1")))
        assertTrue(details.load(PROFILE, ObjectRef("web01", "svc4")) != null)
    }

    @Test
    fun `reopening a record keeps it from being dropped`() = runBlocking {
        listOf("a", "b", "c").forEach { name ->
            clock.advance(Duration.ofSeconds(1))
            dao.saveOpened(row(name), openedAt = clock.millis(), keep = 3)
        }
        clock.advance(Duration.ofSeconds(1))
        dao.saveOpened(row("a"), openedAt = clock.millis(), keep = 3)
        clock.advance(Duration.ofSeconds(1))
        dao.saveOpened(row("d"), openedAt = clock.millis(), keep = 3)

        assertTrue("reopened, so recent", details.load(PROFILE, ObjectRef("web01", "a")) != null)
        assertNull("now the oldest", details.load(PROFILE, ObjectRef("web01", "b")))
    }

    @Test
    fun `a record dropped from the opened list stays if the poll still holds it, without its comments`() = runBlocking {
        cache.savePoll(PROFILE, poll(problems = listOf(service("Disk /"))))
        clock.advance(Duration.ofSeconds(1))
        dao.saveOpened(row("Disk /"), openedAt = clock.millis(), keep = 1)
        details.saveComments(PROFILE, ObjectRef("web01", "Disk /"), listOf(comment(1)))
        clock.advance(Duration.ofSeconds(1))

        dao.saveOpened(row("Other"), openedAt = clock.millis(), keep = 1)

        assertEquals(
            "still a problem in the poll",
            listOf("Disk /"),
            cache.loadPoll(PROFILE)?.serviceProblems?.map {
                it.description
            },
        )
        assertTrue(details.loadComments(PROFILE, ObjectRef("web01", "Disk /")).items.isEmpty())
    }

    @Test
    fun `comments are stored newest first and capped`() = runBlocking {
        val ref = ObjectRef("web01", "Disk /")
        details.saveOpened(PROFILE, service("Disk /"))

        details.saveComments(PROFILE, ref, (1L..30L).map(::comment))

        val loaded = details.loadComments(PROFILE, ref)
        assertEquals(DetailCache.MAX_ANNOTATIONS, loaded.items.size)
        assertEquals("newest kept", 30L, loaded.items.first().id)
        assertEquals(11L, loaded.items.last().id)
        assertEquals(T0, loaded.fetchedAt)
    }

    @Test
    fun `comments keep their kind, author, text and expiry`() = runBlocking {
        val ref = ObjectRef("web01", "Disk /")
        details.saveOpened(PROFILE, service("Disk /"))
        val original = Comment(
            7,
            Comment.Kind.ACKNOWLEDGEMENT,
            "alice",
            "known issue",
            T0,
            true,
            T0 + Duration.ofDays(1),
        )

        details.saveComments(PROFILE, ref, listOf(original))

        assertEquals(listOf(original), details.loadComments(PROFILE, ref).items)
    }

    @Test
    fun `replacing comments removes the ones Nagios no longer has`() = runBlocking {
        val ref = ObjectRef("web01", "Disk /")
        details.saveOpened(PROFILE, service("Disk /"))
        details.saveComments(PROFILE, ref, listOf(comment(1), comment(2)))

        details.saveComments(PROFILE, ref, listOf(comment(2)))

        assertEquals(listOf(2L), details.loadComments(PROFILE, ref).items.map { it.id })
    }

    @Test
    fun `comments never fetched are distinguishable from none`() = runBlocking {
        details.saveOpened(PROFILE, service("Disk /"))

        val never = details.loadComments(PROFILE, ObjectRef("web01", "Disk /"))
        assertNull("never fetched", never.fetchedAt)

        details.saveComments(PROFILE, ObjectRef("web01", "Disk /"), emptyList())
        assertEquals(
            "fetched, and there were none",
            T0,
            details.loadComments(PROFILE, ObjectRef("web01", "Disk /")).fetchedAt,
        )
    }

    @Test
    fun `a host's comments and a service's are kept apart`() = runBlocking {
        details.saveOpened(PROFILE, HostStatus("web01", HostState.UP, CheckStatus()))
        details.saveOpened(PROFILE, service("Disk /"))

        details.saveComments(PROFILE, ObjectRef("web01"), listOf(comment(1)))
        details.saveComments(PROFILE, ObjectRef("web01", "Disk /"), listOf(comment(2)))

        assertEquals(listOf(1L), details.loadComments(PROFILE, ObjectRef("web01")).items.map { it.id })
        assertEquals(listOf(2L), details.loadComments(PROFILE, ObjectRef("web01", "Disk /")).items.map { it.id })
        assertTrue(details.load(PROFILE, ObjectRef("web01")) is CachedDetail.Host)
    }

    @Test
    fun `downtimes survive a round trip`() = runBlocking {
        val ref = ObjectRef("web01")
        details.saveOpened(PROFILE, HostStatus("web01", HostState.UP, CheckStatus()))
        val flexible =
            Downtime(9, "alice", "maintenance", T0, T0 + Duration.ofHours(4), false, Duration.ofHours(1), false)
        val fixed =
            Downtime(8, "alice", "maintenance", T0 - Duration.ofHours(1), T0 + Duration.ofHours(1), true, null, true)

        details.saveDowntimes(PROFILE, ref, listOf(flexible, fixed))

        assertEquals("soonest start first", listOf(fixed, flexible), details.loadDowntimes(PROFILE, ref).items)
    }

    @Test
    fun `deleting a profile clears everything held for it and nothing else`() = runBlocking {
        listOf(PROFILE, OTHER).forEach { profile ->
            cache.savePoll(
                profile,
                poll(
                    problems = listOf(service("Disk /")),
                    states = listOf(state("web01", "Disk /", ServiceState.CRITICAL)),
                ),
            )
            details.saveOpened(profile, service("Disk /"))
            details.saveComments(profile, ObjectRef("web01", "Disk /"), listOf(comment(1)))
            details.saveDowntimes(
                profile,
                ObjectRef("web01", "Disk /"),
                listOf(Downtime(1, "a", "c", T0, T0, true, null, false)),
            )
        }

        cache.onProfileDeleted(PROFILE)

        assertNull(cache.loadPoll(PROFILE))
        assertNull(details.load(PROFILE, ObjectRef("web01", "Disk /")))
        assertTrue(details.loadComments(PROFILE, ObjectRef("web01", "Disk /")).items.isEmpty())
        assertTrue(details.loadDowntimes(PROFILE, ObjectRef("web01", "Disk /")).items.isEmpty())
        assertTrue(dao.serviceStates(PROFILE).isEmpty())
        assertEquals(
            "the other profile is untouched",
            1,
            details.loadComments(OTHER, ObjectRef("web01", "Disk /")).items.size,
        )
        assertEquals(1, cache.loadPoll(OTHER)?.serviceProblems?.size)
    }

    @Test
    fun `the orphan sweep removes cache for profiles that no longer exist`() = runBlocking {
        listOf(PROFILE, OTHER).forEach { cache.savePoll(it, poll(problems = listOf(service("Disk /")))) }

        cache.sweepOrphans(listOf(OTHER))

        assertNull(cache.loadPoll(PROFILE))
        assertEquals(1, cache.loadPoll(OTHER)?.serviceProblems?.size)
    }

    @Test
    fun `with no profiles left the sweep clears everything`() = runBlocking {
        cache.savePoll(PROFILE, poll(problems = listOf(service("Disk /"))))

        cache.sweepOrphans(emptyList())

        assertNull(cache.loadPoll(PROFILE))
    }

    @Test
    fun `a state this version does not know reads as a problem, not as fine`() = runBlocking {
        dao.replacePoll(
            PROFILE,
            states = listOf(ServiceStateRow(PROFILE, "web01", "Odd", "SOMETHING_NEW")),
            details = listOf(row("Odd").copy(state = "SOMETHING_NEW"), row("").copy(isHost = true, state = "ALSO_NEW")),
            fetchedAt = T0.toEpochMilli(),
        )

        val loaded = checkNotNull(cache.loadPoll(PROFILE))
        assertEquals(ServiceState.UNKNOWN, loaded.serviceStates.single().state)
        assertEquals(ServiceState.UNKNOWN, loaded.serviceProblems.single().state)
        assertEquals(HostState.UNREACHABLE, loaded.hosts.single().state)
    }

    private fun poll(
        at: Instant = T0,
        hosts: List<HostStatus> = listOf(HostStatus("web01", HostState.UP, CheckStatus())),
        problems: List<ServiceStatus> = emptyList(),
        states: List<ServiceStateEntry> = emptyList(),
    ) = StatusSnapshot(hosts, problems, at, states)

    private fun service(name: String, state: ServiceState = ServiceState.CRITICAL) =
        ServiceStatus("web01", name, state, CheckStatus(pluginOutput = "output of $name"))

    private fun state(host: String, name: String, state: ServiceState) = ServiceStateEntry(host, name, state)

    private fun comment(id: Long) = Comment(
        id = id,
        kind = Comment.Kind.USER,
        author = "alice",
        text = "comment $id",
        enteredAt = T0 + Duration.ofMinutes(id),
        persistent = true,
        expiresAt = null,
    )

    /** A raw detail row, for driving the DAO with a small `keep`. */
    private fun row(description: String) = DetailRow(
        profileId = PROFILE,
        hostName = "web01",
        description = description,
        isHost = false,
        state = "CRITICAL",
        stateType = "HARD",
        output = "",
        longOutput = "",
        perfData = "",
        currentAttempt = 1,
        maxAttempts = 3,
        lastCheck = null,
        nextCheck = null,
        lastStateChange = null,
        acknowledged = false,
        downtimeDepth = 0,
        checksEnabled = true,
        notificationsEnabled = true,
        activeCheck = true,
        flapping = false,
        detailsAvailable = true,
        fetchedAt = T0.toEpochMilli(),
        inPoll = false,
        openedAt = null,
    )

    private companion object {
        const val PROFILE = 1L
        const val OTHER = 2L
    }
}
