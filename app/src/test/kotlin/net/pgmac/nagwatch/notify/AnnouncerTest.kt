// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import java.time.Instant
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.notify.Notice.Change
import net.pgmac.nagwatch.status.StatusError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a background poll is worth saying. Every poll here is a real one, classified the way the
 * app classifies it, so that "handled" and "under a host that is down" mean what they mean on screen.
 */
class AnnouncerTest {
    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")
    private val web01 = ObjectRef("web01")

    /** A profile that has had its first poll, with nothing wrong. */
    private val quiet = AnnouncedState(initialised = true)

    // ---------------------------------------------------------------- the first poll

    @Test
    fun `the first good poll is one summary of everything, and nothing is itemised`() {
        val decision = poll(AnnouncedState(), report(critical(disk), warning(load)))

        assertEquals(listOf<Notice>(Notice.Summary(count = 2, first = true)), decision.post)
        assertTrue(decision.state.initialised)
        assertEquals(
            mapOf(disk to Known(Severity.CRITICAL, individually = false), load to Known(Severity.WARNING, false)),
            decision.state.known,
        )
    }

    @Test
    fun `a first poll with nothing wrong says nothing, and is still the first poll done`() {
        val decision = poll(AnnouncedState(), report())

        assertTrue(decision.post.isEmpty())
        assertTrue(decision.state.initialised)
    }

    @Test
    fun `the first summary counts what would be announced, so not a SOFT problem or a handled one`() {
        val first =
            report(
                critical(disk),
                critical(load, soft = true),
                critical(ObjectRef("web01", "HTTP"), acknowledged = true),
            )

        assertEquals(listOf<Notice>(Notice.Summary(count = 1, first = true)), poll(AnnouncedState(), first).post)
    }

    @Test
    fun `a failed poll is not the first poll, so the first good one after it is still summarised`() {
        val afterFailure = fail(AnnouncedState(), timeout).state
        assertFalse(afterFailure.initialised)

        assertEquals(
            listOf<Notice>(Notice.Summary(count = 1, first = true)),
            poll(afterFailure, report(critical(disk))).post,
        )
    }

    @Test
    fun `what was in the first summary does not produce a recovery notice of its own`() {
        val first = poll(AnnouncedState(), report(critical(disk))).state

        val recovered = poll(first, report())

        assertTrue(recovered.post.isEmpty())
        assertTrue(recovered.remove.isEmpty())
        assertTrue(recovered.state.known.isEmpty())
    }

    // ---------------------------------------------------------------- new, and the same again

    @Test
    fun `a new HARD unhandled problem is announced by a notification of its own`() {
        val decision = poll(quiet, report(critical(disk)))

        val raised = decision.post.single() as Notice.Raised
        assertEquals(disk, raised.ref)
        assertEquals(Change.NEW, raised.change)
        assertEquals(Severity.CRITICAL, raised.problem.severity)
        assertEquals(mapOf(disk to Known(Severity.CRITICAL, individually = true)), decision.state.known)
    }

    @Test
    fun `the same problem at the next poll is not announced again`() {
        val once = poll(quiet, report(critical(disk))).state

        val again = poll(once, report(critical(disk)))

        assertTrue(again.post.isEmpty())
        assertTrue(again.remove.isEmpty())
        assertEquals(once, again.state)
    }

    @Test
    fun `a SOFT problem is not announced, and is when it becomes HARD`() {
        val soft = poll(quiet, report(critical(disk, soft = true)))
        assertTrue(soft.post.isEmpty())
        assertTrue("it is not yet known at all", soft.state.known.isEmpty())

        val hard = poll(soft.state, report(critical(disk)))
        assertEquals(Change.NEW, (hard.post.single() as Notice.Raised).change)
    }

    @Test
    fun `a host going down is one announcement, about the host`() {
        val decision =
            poll(quiet, report(critical(disk), critical(load), hosts = listOf(host("web01", HostState.DOWN))))

        val raised = decision.post.single() as Notice.Raised
        assertEquals(web01, raised.ref)
        assertEquals(Severity.HOST_DOWN, raised.problem.severity)
    }

    @Test
    fun `a record Nagios could not give in full is announced, since it cannot be shown to be SOFT or handled`() {
        val degraded = ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(detailsAvailable = false))

        assertEquals(Change.NEW, (poll(quiet, report(degraded)).post.single() as Notice.Raised).change)
    }

    // ---------------------------------------------------------------- worse, and better

    @Test
    fun `getting worse is announced again, up every step of the app's order of severity`() {
        val steps = listOf(
            unknown(disk) to critical(disk),
            unknown(disk) to warning(disk),
            warning(disk) to critical(disk),
        )

        steps.forEach { (before, after) ->
            val known = poll(quiet, report(before)).state
            val worse = poll(known, report(after))

            val raised = worse.post.single() as Notice.Raised
            assertEquals("$before -> $after", Change.WORSE, raised.change)
            assertEquals(after.state.toSeverity(), worse.state.known.getValue(disk).severity)
        }
    }

    @Test
    fun `a host that was unreachable and is now down is worse`() {
        val unreachable = poll(quiet, report(hosts = listOf(host("web01", HostState.UNREACHABLE)))).state

        val down = poll(unreachable, report(hosts = listOf(host("web01", HostState.DOWN))))

        assertEquals(Change.WORSE, (down.post.single() as Notice.Raised).change)
    }

    @Test
    fun `getting better while still a problem brings its notification up to date without interrupting`() {
        val known = poll(quiet, report(critical(disk))).state

        val better = poll(known, report(warning(disk)))

        assertEquals(Change.UPDATED, (better.post.single() as Notice.Raised).change)
        assertEquals(Known(Severity.WARNING, individually = true), better.state.known.getValue(disk))
    }

    @Test
    fun `getting better says nothing when there is no notification of its own to bring up to date`() {
        val summarised = poll(AnnouncedState(), report(critical(disk))).state

        val better = poll(summarised, report(warning(disk)))

        assertTrue(better.post.isEmpty())
        assertEquals(Known(Severity.WARNING, individually = false), better.state.known.getValue(disk))
    }

    @Test
    fun `something from the first summary that gets worse is announced, and has its own from then on`() {
        val summarised = poll(AnnouncedState(), report(warning(disk))).state

        val worse = poll(summarised, report(critical(disk)))

        assertEquals(Change.WORSE, (worse.post.single() as Notice.Raised).change)
        assertTrue(worse.state.known.getValue(disk).individually)
    }

    // ---------------------------------------------------------------- dealt with

    @Test
    fun `being acknowledged takes the notification down without a word`() {
        val known = poll(quiet, report(critical(disk))).state

        val acknowledged = poll(known, report(critical(disk, acknowledged = true)))

        assertTrue(acknowledged.post.isEmpty())
        assertEquals(listOf(disk), acknowledged.remove)
        assertTrue(acknowledged.state.known.isEmpty())
    }

    @Test
    fun `going into downtime, its own or its host's, does the same`() {
        val known = poll(quiet, report(critical(disk))).state

        val own = poll(known, report(critical(disk, downtime = 1)))
        val hosts = poll(known, report(critical(disk), hosts = listOf(host("web01", downtime = 1))))

        assertEquals(listOf(disk), own.remove)
        assertEquals(listOf(disk), hosts.remove)
        assertTrue(own.post.isEmpty() && hosts.post.isEmpty())
    }

    @Test
    fun `when its host goes down a service's notification gives way to the host's`() {
        val known = poll(quiet, report(critical(disk), warning(load))).state

        val down = poll(known, report(critical(disk), warning(load), hosts = listOf(host("web01", HostState.DOWN))))

        assertEquals(web01, (down.post.single() as Notice.Raised).ref)
        assertEquals(setOf(disk, load), down.remove.toSet())
        assertEquals(setOf(web01), down.state.known.keys)
    }

    @Test
    fun `a host that goes down already in downtime still takes its services' notifications with it`() {
        val known = poll(quiet, report(critical(disk))).state

        val down = poll(known, report(critical(disk), hosts = listOf(host("web01", HostState.DOWN, downtime = 1))))

        assertTrue("nobody is told about a host that is being dealt with", down.post.isEmpty())
        assertEquals("and the service has not recovered", listOf(disk), down.remove)
    }

    @Test
    fun `a problem that is handled and then unhandled again, still broken, is new`() {
        val known = poll(quiet, report(critical(disk))).state
        val acknowledged = poll(known, report(critical(disk, acknowledged = true))).state

        val again = poll(acknowledged, report(critical(disk)))

        assertEquals(Change.NEW, (again.post.single() as Notice.Raised).change)
    }

    @Test
    fun `a handled problem that was never announced is nothing to take down`() {
        val summarised = poll(AnnouncedState(), report(critical(disk))).state

        val acknowledged = poll(summarised, report(critical(disk, acknowledged = true)))

        assertTrue(acknowledged.remove.isEmpty())
        assertTrue(acknowledged.post.isEmpty())
    }

    // ---------------------------------------------------------------- recovered

    @Test
    fun `a recovery is announced for something that had a notification of its own`() {
        val known = poll(quiet, report(critical(disk))).state

        val recovered = poll(known, report())

        assertEquals(listOf<Notice>(Notice.Recovered(disk)), recovered.post)
        assertTrue("it replaces the notification, it does not remove it", recovered.remove.isEmpty())
        assertTrue(recovered.state.known.isEmpty())
    }

    @Test
    fun `after a recovery the same thing failing again is new`() {
        val known = poll(quiet, report(critical(disk))).state
        val recovered = poll(known, report()).state

        assertEquals(Change.NEW, (poll(recovered, report(critical(disk))).post.single() as Notice.Raised).change)
    }

    @Test
    fun `recovering and failing again within one poll, only SOFT so far, is a recovery`() {
        val known = poll(quiet, report(critical(disk))).state

        val softAgain = poll(known, report(critical(disk, soft = true)))

        assertEquals(listOf<Notice>(Notice.Recovered(disk)), softAgain.post)
        assertTrue(softAgain.state.known.isEmpty())
    }

    @Test
    fun `a host coming back up is a recovery of the host`() {
        val down = poll(quiet, report(hosts = listOf(host("web01", HostState.DOWN)))).state

        assertEquals(listOf<Notice>(Notice.Recovered(web01)), poll(down, report()).post)
    }

    // ---------------------------------------------------------------- many at once

    @Test
    fun `five at once are five notifications`() {
        val decision = poll(quiet, report(*criticals(Announcer.CEILING)))

        assertEquals(Announcer.CEILING, decision.post.size)
        assertTrue(decision.post.all { it is Notice.Raised && it.change == Change.NEW })
        assertTrue(decision.state.known.values.all { it.individually })
    }

    @Test
    fun `six at once are one summary, and none of them has a notification of its own`() {
        val decision = poll(quiet, report(*criticals(Announcer.CEILING + 1)))

        assertEquals(listOf<Notice>(Notice.Summary(count = Announcer.CEILING + 1, first = false)), decision.post)
        assertEquals(Announcer.CEILING + 1, decision.state.known.size)
        assertTrue(decision.state.known.values.none { it.individually })
    }

    @Test
    fun `what a summary stood for recovers without a notice, and is announced if it gets worse`() {
        val many = arrayOf(*warnings(Announcer.CEILING + 1))
        val summarised = poll(quiet, report(*many)).state

        assertTrue(poll(summarised, report()).post.isEmpty())

        val first = ObjectRef("web01", "check 0")
        val oneWorse = poll(summarised, report(critical(first), *many.drop(1).toTypedArray()))
        assertEquals(first, (oneWorse.post.single() as Notice.Raised).ref)
        assertEquals(Change.WORSE, (oneWorse.post.single() as Notice.Raised).change)
    }

    @Test
    fun `under a summary, something that already had its own notification has it brought up to date`() {
        val known = poll(quiet, report(warning(disk))).state

        val storm = poll(known, report(critical(disk), *criticals(Announcer.CEILING)))

        assertEquals(
            "the worse one counts towards the summary",
            Notice.Summary(Announcer.CEILING + 1, first = false),
            storm.post.first(),
        )
        val updated = storm.post.filterIsInstance<Notice.Raised>().single()
        assertEquals(disk, updated.ref)
        assertEquals(Change.UPDATED, updated.change)
        assertTrue("it keeps its own notification", storm.state.known.getValue(disk).individually)
    }

    @Test
    fun `a summary counts what is new or worse, not what was already known`() {
        val known = poll(quiet, report(critical(disk), warning(load))).state

        val storm = poll(known, report(critical(disk), warning(load), *criticals(Announcer.CEILING + 1)))

        assertEquals(listOf<Notice>(Notice.Summary(Announcer.CEILING + 1, first = false)), storm.post)
    }

    @Test
    fun `getting worse under a summary does not give a problem a notification it never had`() {
        val many = warnings(Announcer.CEILING + 1)
        val summarised = poll(quiet, report(*many)).state
        val first = ObjectRef("web01", "check 0")
        val more = Array(Announcer.CEILING) { critical(ObjectRef("db01", "check $it")) }

        // One of the summarised gets worse in a poll that is itself summarised.
        val storm = poll(summarised, report(critical(first), *many.drop(1).toTypedArray(), *more))

        assertEquals(listOf<Notice>(Notice.Summary(Announcer.CEILING + 1, first = false)), storm.post)
        assertEquals(Known(Severity.CRITICAL, individually = false), storm.state.known.getValue(first))

        val recovered = poll(storm.state, report(*many.drop(1).toTypedArray(), *more))
        assertTrue("so it has nothing to turn into a recovery", recovered.post.isEmpty())
    }

    @Test
    fun `quiet changes do not count towards the ceiling`() {
        val five = criticals(Announcer.CEILING)
        val known = poll(quiet, report(critical(disk), warning(load))).state

        // Five new, one better, one recovered: five notifications, not a summary.
        val decision = poll(known, report(warning(disk), *five))

        assertEquals(Announcer.CEILING, decision.post.count { it is Notice.Raised && it.change == Change.NEW })
        assertTrue(decision.post.none { it is Notice.Summary })
        assertTrue(Notice.Recovered(load) in decision.post)
    }

    // ---------------------------------------------------------------- only unhandled, switched off

    @Test
    fun `with only-unhandled off, a handled problem is announced like any other`() {
        val everything = NotifySettings(unhandledOnly = false)

        val decision = poll(quiet, report(critical(disk, acknowledged = true)), everything)

        assertEquals(Change.NEW, (decision.post.single() as Notice.Raised).change)
    }

    @Test
    fun `with only-unhandled off, being acknowledged changes nothing`() {
        val everything = NotifySettings(unhandledOnly = false)
        val known = poll(quiet, report(critical(disk)), everything).state

        val acknowledged = poll(known, report(critical(disk, acknowledged = true)), everything)

        assertTrue(acknowledged.post.isEmpty())
        assertTrue(acknowledged.remove.isEmpty())
        assertEquals(known, acknowledged.state)
    }

    // ---------------------------------------------------------------- failed polls

    @Test
    fun `a failure that may pass is said on the third in a row, once`() {
        val first = fail(quiet, timeout)
        val second = fail(first.state, timeout)
        val third = fail(second.state, timeout)
        val fourth = fail(third.state, timeout)

        assertTrue(first.post.isEmpty() && second.post.isEmpty())
        assertEquals(listOf<Notice>(Notice.Outage(OutageCause.UNREACHABLE, timeout, quiet = false)), third.post)
        assertTrue("already said", fourth.post.isEmpty())
        assertEquals(4, fourth.state.failures)
        assertEquals(OutageCause.UNREACHABLE, fourth.state.outage)
    }

    @Test
    fun `the next good poll takes the outage notice down and starts the count again`() {
        val out = (1..3).fold(quiet) { state, _ -> fail(state, timeout).state }

        val back = poll(out, report())

        assertTrue(back.removeOutage)
        assertEquals(0, back.state.failures)
        assertNull(back.state.outage)
    }

    @Test
    fun `a good poll with no outage announced has nothing to take down`() {
        val twice = (1..2).fold(quiet) { state, _ -> fail(state, timeout).state }

        assertFalse(poll(twice, report()).removeOutage)
    }

    @Test
    fun `failures have to be in a row`() {
        val state = listOf(false, false, true, false, false).fold(quiet) { state, good ->
            val decision = if (good) poll(state, report()) else fail(state, timeout)
            assertTrue("nothing is said at any point", decision.post.isEmpty())
            decision.state
        }

        assertEquals(2, state.failures)
    }

    @Test
    fun `with the profile's switch off, a failure that may pass is never said`() {
        val silent = NotifySettings(tellUnreachable = false)

        val state = (1..5).fold(quiet) { state, _ ->
            val decision = fail(state, timeout, silent)
            assertTrue(decision.post.isEmpty())
            decision.state
        }

        assertNull(state.outage)
        assertEquals(5, state.failures)
    }

    @Test
    fun `a failure that will not pass is said at once, whatever the switch says`() {
        val willNot = listOf(
            StatusError.Nagios(NagiosError.BadCredentials) to OutageCause.CREDENTIALS_REJECTED,
            StatusError.Nagios(NagiosError.AccessRejected) to OutageCause.ACCESS_REJECTED,
            StatusError.Nagios(NagiosError.Forbidden) to OutageCause.NOT_ALLOWED,
            StatusError.CredentialsUnavailable to OutageCause.CREDENTIALS_UNREADABLE,
            StatusError.InvalidUrl to OutageCause.MISCONFIGURED,
            StatusError.Nagios(NagiosError.CleartextRefused) to OutageCause.MISCONFIGURED,
            StatusError.Nagios(NagiosError.AccessOverCleartext) to OutageCause.MISCONFIGURED,
        )

        willNot.forEach { (error, cause) ->
            val decision = fail(quiet, error, NotifySettings(tellUnreachable = false))

            assertEquals("$error", listOf<Notice>(Notice.Outage(cause, error, quiet = false)), decision.post)
            assertFalse(cause.mayPass)
        }
    }

    @Test
    fun `everything else may pass, and waits`() {
        val mayPass = listOf(
            StatusError.Nagios(NagiosError.Unreachable(NagiosError.Unreachable.Reason.DNS, "")) to
                OutageCause.UNREACHABLE,
            StatusError.Nagios(NagiosError.Unreachable(NagiosError.Unreachable.Reason.REFUSED, "")) to
                OutageCause.UNREACHABLE,
            timeout to OutageCause.UNREACHABLE,
            StatusError.Nagios(NagiosError.Certificate("expired")) to OutageCause.CERTIFICATE,
            StatusError.Nagios(NagiosError.NotNagios) to OutageCause.WRONG_ANSWER,
            StatusError.Nagios(NagiosError.Redirected("https://portal.example")) to OutageCause.WRONG_ANSWER,
            StatusError.Nagios(NagiosError.Http(502)) to OutageCause.SERVER_ERROR,
            StatusError.Nagios(NagiosError.Api(8, "error", "x")) to OutageCause.SERVER_ERROR,
            StatusError.Nagios(NagiosError.ResponseTooLarge) to OutageCause.SERVER_ERROR,
            StatusError.Nagios(NagiosError.LocalNetworkBlocked) to OutageCause.LOCAL_NETWORK,
        )

        mayPass.forEach { (error, cause) ->
            assertEquals("$error", cause, OutageCause.of(error))
            assertTrue("$cause", cause.mayPass)
            assertTrue("$error is not said on the first failure", fail(quiet, error).post.isEmpty())
        }
    }

    @Test
    fun `when the cause of an outage changes the notice is replaced, without interrupting again`() {
        val out = (1..3).fold(quiet) { state, _ -> fail(state, timeout).state }
        val rejected = StatusError.Nagios(NagiosError.BadCredentials)

        val changed = fail(out, rejected)

        assertEquals(
            listOf<Notice>(Notice.Outage(OutageCause.CREDENTIALS_REJECTED, rejected, quiet = true)),
            changed.post,
        )
        assertEquals(OutageCause.CREDENTIALS_REJECTED, changed.state.outage)
    }

    @Test
    fun `a notice that is no longer true comes down, even when what is wrong now is not yet worth saying`() {
        val rejected = fail(quiet, StatusError.Nagios(NagiosError.BadCredentials)).state

        // The password was put right, and now the server just is not answering: the second failure in a row.
        val unreachable = fail(rejected, timeout)

        assertTrue(unreachable.post.isEmpty())
        assertTrue(unreachable.removeOutage)
        assertNull(unreachable.state.outage)

        val third = fail(unreachable.state, timeout)
        assertEquals(listOf<Notice>(Notice.Outage(OutageCause.UNREACHABLE, timeout, quiet = false)), third.post)
    }

    @Test
    fun `a failed poll changes nothing that is known about the problems, and can announce no recovery`() {
        val known = poll(quiet, report(critical(disk), warning(load))).state

        val decision = (1..4).fold(Decision(state = known)) { last, _ ->
            val next = fail(last.state, timeout)
            assertTrue(next.post.none { it is Notice.Recovered || it is Notice.Raised || it is Notice.Summary })
            assertTrue(next.remove.isEmpty())
            next
        }

        assertEquals(known.known, decision.state.known)
        assertTrue(decision.state.initialised)
    }

    @Test
    fun `the good poll that ends an outage is an ordinary poll in every other way`() {
        val known = poll(quiet, report(critical(disk))).state
        val out = (1..3).fold(known) { state, _ -> fail(state, timeout).state }

        val back = poll(out, report(critical(load)))

        assertTrue(back.removeOutage)
        assertTrue(Notice.Recovered(disk) in back.post)
        assertEquals(load, back.post.filterIsInstance<Notice.Raised>().single().ref)
    }

    @Test
    fun `a profile that no longer exists changes nothing and says nothing`() {
        val known = poll(quiet, report(critical(disk))).state

        val decision = fail(known, StatusError.ProfileMissing)

        assertEquals(Decision(state = known), decision)
    }

    // ---------------------------------------------------------------- helpers

    private val timeout = StatusError.Nagios(NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, ""))

    private fun poll(state: AnnouncedState, report: ProblemReport, settings: NotifySettings = NotifySettings()) =
        Announcer.decide(state, PollOutcome.Polled(report), settings)

    private fun fail(state: AnnouncedState, error: StatusError, settings: NotifySettings = NotifySettings()) =
        Announcer.decide(state, PollOutcome.Failed(error), settings)

    /** A poll as the app would classify it. Every service's host is up unless [hosts] says otherwise. */
    private fun report(vararg services: ServiceStatus, hosts: List<HostStatus> = emptyList()): ProblemReport {
        val named = hosts.map { it.name }.toSet()
        val implied = services.map { it.hostName }.distinct().filterNot { it in named }.map { host(it) }
        return ProblemClassifier.classify(StatusSnapshot(hosts + implied, services.toList(), NOW))
    }

    private fun host(name: String, state: HostState = HostState.UP, downtime: Int = 0) =
        HostStatus(name, state, CheckStatus(downtimeDepth = downtime))

    private fun service(
        ref: ObjectRef,
        state: ServiceState,
        soft: Boolean = false,
        acknowledged: Boolean = false,
        downtime: Int = 0,
    ) = ServiceStatus(
        hostName = ref.hostName,
        description = checkNotNull(ref.description),
        state = state,
        check = CheckStatus(
            stateType = if (soft) StateType.SOFT else StateType.HARD,
            acknowledged = acknowledged,
            downtimeDepth = downtime,
        ),
    )

    private fun critical(ref: ObjectRef, soft: Boolean = false, acknowledged: Boolean = false, downtime: Int = 0) =
        service(ref, ServiceState.CRITICAL, soft, acknowledged, downtime)

    private fun warning(ref: ObjectRef) = service(ref, ServiceState.WARNING)

    private fun unknown(ref: ObjectRef) = service(ref, ServiceState.UNKNOWN)

    private fun criticals(count: Int) = Array(count) { critical(ObjectRef("web01", "check $it")) }

    private fun warnings(count: Int) = Array(count) { warning(ObjectRef("web01", "check $it")) }

    private fun ServiceState.toSeverity(): Severity = when (this) {
        ServiceState.CRITICAL -> Severity.CRITICAL
        ServiceState.WARNING -> Severity.WARNING
        else -> Severity.UNKNOWN
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T00:00:00Z")
    }
}
