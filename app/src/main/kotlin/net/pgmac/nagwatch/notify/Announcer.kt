// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.status.StatusError

/**
 * Decides what one background poll is worth saying, given what has been said already.
 *
 * A plain function of its arguments, with no Android and no clock in it: everything that makes
 * a notification right or wrong is here, where it can be tested exhaustively. The rules, and
 * why, are in docs/adr/0008.
 *
 * - Only a HARD problem is announced. A SOFT one is Nagios still making up its mind.
 * - The first successful poll is summarised: the person was just looking at that list.
 * - A problem is announced when it is new, and again when it gets worse.
 * - More than [CEILING] at once are summarised.
 * - Being dealt with (acknowledged, in downtime, its host down) takes a notification away quietly.
 * - A recovery is said only of something that had a notification of its own.
 * - A failed poll never changes what is known about the problems. It cannot announce a
 *   recovery, and it can never amount to "all OK".
 */
object Announcer {
    /** How many notifications one poll may raise before it is one summary instead. */
    const val CEILING = 5

    /** How many polls in a row must fail before an outage that may pass is worth a notification. */
    const val FAILURES_BEFORE_TELLING = 3

    fun decide(state: AnnouncedState, outcome: PollOutcome, settings: NotifySettings): Decision = when (outcome) {
        is PollOutcome.Polled -> polled(state, outcome.report, settings)
        is PollOutcome.Failed -> failed(state, outcome.error, settings)
    }

    private fun polled(state: AnnouncedState, report: ProblemReport, settings: NotifySettings): Decision {
        val current = announceable(report, settings)
        // Nagios answered, so whatever outage was announced is over.
        val recovered = state.copy(failures = 0, outage = null)
        val outageOver = state.outage != null
        if (!state.initialised) {
            // Everything there is, as one line. None of it is announced individually, so none
            // of it will produce a recovery notice of its own later.
            return Decision(
                post = if (current.isEmpty()) emptyList() else listOf(Notice.Summary(current.size, first = true)),
                removeOutage = outageOver,
                state = recovered.copy(
                    initialised = true,
                    known = current.mapValues { Known(it.value.severity, individually = false) },
                ),
            )
        }

        val changes = current.map { (ref, problem) -> Seen(ref, problem, state.known[ref]) }
        val loud = changes.filter { it.isNew || it.isWorse }
        val summarised = loud.size > CEILING

        val (recoveries, dealtWith) = gone(state, current, report).partition { it.recovered }

        val post = buildList {
            if (summarised) add(Notice.Summary(loud.size, first = false))
            changes.forEach { seen -> seen.notice(summarised)?.let(::add) }
            recoveries.forEach { add(Notice.Recovered(it.ref)) }
        }
        return Decision(
            post = post,
            remove = dealtWith.map { it.ref },
            removeOutage = outageOver,
            state = recovered.copy(known = changes.associate { it.ref to it.known(summarised) }),
        )
    }

    /** One problem in this poll, beside what was known of it before. */
    private class Seen(val ref: ObjectRef, val problem: Problem, val before: Known?) {
        val isNew: Boolean get() = before == null

        // The order of the enum is the order of severity, most severe first.
        val isWorse: Boolean get() = before != null && problem.severity < before.severity
        private val isBetter: Boolean get() = before != null && problem.severity > before.severity
        private val hasItsOwn: Boolean get() = before?.individually == true

        /** What to show for it, if anything. Under a summary nothing new is raised one by one. */
        fun notice(summarised: Boolean): Notice? = when {
            (isNew || isWorse) && !summarised ->
                Notice.Raised(ref, problem, if (isNew) Notice.Change.NEW else Notice.Change.WORSE)

            // Its own notification is showing and no longer says the right thing.
            (isWorse || isBetter) && hasItsOwn -> Notice.Raised(ref, problem, Notice.Change.UPDATED)

            else -> null
        }

        fun known(summarised: Boolean): Known = Known(
            severity = problem.severity,
            individually = when {
                summarised && isNew -> false
                isNew || isWorse -> !summarised || hasItsOwn
                else -> hasItsOwn
            },
        )
    }

    /** Something that was known and is no longer announceable, and why. */
    private class Gone(val ref: ObjectRef, val recovered: Boolean)

    /**
     * What has left the list, limited to what had a notification of its own: only those have
     * anything to take down or to replace.
     *
     * Still being a problem but handled (or under a host that is down) is "dealt with": the
     * notification goes quietly. Anything else is a recovery, including a problem that
     * recovered and has since failed again but is only SOFT.
     */
    private fun gone(state: AnnouncedState, current: Map<ObjectRef, Problem>, report: ProblemReport): List<Gone> {
        val dealtWith = dealtWith(report)
        return state.known
            .filter { (ref, known) -> ref !in current && known.individually }
            .map { (ref, _) -> Gone(ref, recovered = ref !in dealtWith) }
    }

    /** Problems this poll is entitled to announce: HARD, and unhandled unless the profile asks for all. */
    private fun announceable(report: ProblemReport, settings: NotifySettings): Map<ObjectRef, Problem> {
        val considered = if (settings.unhandledOnly) report.unhandled else report.unhandled + report.handled
        return considered.filter { Marker.SOFT !in it.markers }.associateBy { it.ref }
    }

    /** Everything that is still a problem and is being dealt with: handled, or rolled up under its host. */
    private fun dealtWith(report: ProblemReport): Set<ObjectRef> {
        val rolledUp = (report.unhandled + report.handled)
            .filterIsInstance<HostProblem>()
            .flatMap { host -> host.services.map { it.ref } }
        return (report.handled.map { it.ref } + rolledUp).toSet()
    }

    private fun failed(state: AnnouncedState, error: StatusError, settings: NotifySettings): Decision {
        val cause = OutageCause.of(error) ?: return Decision(state = state)
        val failures = state.failures + 1
        val counted = state.copy(failures = failures)
        val worthTelling = !cause.mayPass || (failures >= FAILURES_BEFORE_TELLING && settings.tellUnreachable)
        return when {
            // Already said, and still true.
            cause == state.outage -> Decision(state = counted)

            worthTelling -> Decision(
                post = listOf(Notice.Outage(cause, error, quiet = state.outage != null)),
                state = counted.copy(outage = cause),
            )

            // What was announced is no longer what is wrong, and what is wrong now is not yet worth saying.
            state.outage != null -> Decision(removeOutage = true, state = counted.copy(outage = null))

            else -> Decision(state = counted)
        }
    }
}
