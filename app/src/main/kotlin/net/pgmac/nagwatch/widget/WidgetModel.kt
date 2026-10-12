// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import java.time.Instant
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.nagios.model.firstOutputLine
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.notify.ref
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.Staleness
import net.pgmac.nagwatch.status.cache.PollAttempt

/** How far what a widget shows can be trusted. Each is drawn differently; none is passed off as another. */
enum class WidgetState {
    /** A recent good poll. The only state drawn in full colour. */
    CURRENT,

    /** The last good poll is older than twice the profile's interval, and nothing says a poll failed. */
    STALE,

    /** The most recent poll failed. What is shown, if anything, is from the last one that did not. */
    UNREACHABLE,

    /** Never polled successfully, and no poll has failed either: nothing is known yet. */
    NO_DATA,

    /** The profile this widget was set up for has been deleted. */
    PROFILE_GONE,
}

/** One problem in a widget's list. */
data class WidgetRow(
    val ref: ObjectRef,
    val severity: Severity,
    /** When it entered this state, for "2h 04m"; null if Nagios did not say. */
    val since: Instant?,
    /** One line of plugin output. Empty for a record Nagios could not serialise in full. */
    val output: String,
    /** Nagios is still retrying. Listed and counted, like in the app, and marked. */
    val soft: Boolean,
)

/**
 * Everything a widget shows, whatever its size. The four layouts each draw some of this and
 * decide nothing: what state the data is in, what is counted and in what order are all here.
 */
data class WidgetContent(
    val state: WidgetState,
    val profileName: String? = null,
    /** Unhandled problems by kind. Null when nothing is known, which is not the same as zero. */
    val counts: ProblemCounts? = null,
    /** Problems that are acknowledged or in downtime: shown dimmed where there is room. */
    val handled: Int = 0,
    /** The most severe unhandled problem, for the badge's colour. Null when there is none. */
    val worst: Severity? = null,
    /** Unhandled problems, worst first and then newest, as on the Problems tab. At most [WidgetModel.MAX_ROWS]. */
    val rows: List<WidgetRow> = emptyList(),
    /** Unhandled problems there was no room for in [rows]. */
    val more: Int = 0,
    /** When what is shown was true: the last good poll. Null when there has not been one. */
    val asOf: Instant? = null,
    /** Why the last poll failed, when [state] is [WidgetState.UNREACHABLE]. */
    val cause: OutageCause? = null,
) {
    /** Old or unconfirmed: drawn greyed, with its age and the reason, never as current. */
    val isDemoted: Boolean get() = state == WidgetState.STALE || state == WidgetState.UNREACHABLE
}

/**
 * Decides what a widget shows from what is on disk.
 *
 * A plain function, with no Android and no clock in it. A widget is drawn in whatever process
 * Android has, often one that never saw the poll, so it works from the cache: the last good
 * poll, what the most recent attempt came to, and the profile's interval.
 *
 * The rule that matters is that **old green is never shown as current**. A poll that failed, or
 * one that has not happened for too long, keeps the last numbers (a host that was down twenty
 * minutes ago probably still is) and says how old they are and why.
 */
object WidgetModel {
    /** More rows than any widget can show; a bound on what is handed to the launcher. */
    const val MAX_ROWS = 50

    fun of(profile: Profile?, snapshot: StatusSnapshot?, attempt: PollAttempt?, now: Instant): WidgetContent {
        if (profile == null) return WidgetContent(WidgetState.PROFILE_GONE)
        // The stored attempt is the most recent one, so a failure there is the current truth.
        val failure = attempt?.failure
        if (snapshot == null) {
            val state = if (failure != null) WidgetState.UNREACHABLE else WidgetState.NO_DATA
            return WidgetContent(state, profile.name, cause = failure)
        }
        val staleAfter = Staleness.after(profile.notifications.interval)
        val state = when {
            failure != null -> WidgetState.UNREACHABLE
            Staleness.isStale(snapshot.fetchedAt, now, staleAfter) -> WidgetState.STALE
            else -> WidgetState.CURRENT
        }
        val report = ProblemClassifier.classify(snapshot)
        return WidgetContent(
            state = state,
            profileName = profile.name,
            counts = report.counts,
            handled = report.handled.size,
            worst = report.unhandled.firstOrNull()?.severity,
            rows = report.unhandled.take(MAX_ROWS).map(::row),
            more = (report.unhandled.size - MAX_ROWS).coerceAtLeast(0),
            asOf = snapshot.fetchedAt,
            cause = failure,
        )
    }

    private fun row(problem: Problem) = WidgetRow(
        ref = problem.ref,
        severity = problem.severity,
        since = problem.since,
        output = problem.check.firstOutputLine(),
        soft = Marker.SOFT in problem.markers,
    )
}
