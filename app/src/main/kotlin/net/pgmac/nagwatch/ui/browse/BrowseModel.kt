// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.StatusSnapshot

/**
 * One line of the Hosts or Services list.
 *
 * [check] is null when only the state is known. That is the normal case for a
 * service that is not in a problem state: a poll lists every service by name
 * and state, and fetches details only for the ones with a problem.
 */
data class BrowseRow<S>(
    val hostName: String,
    /** Null for a host. */
    val service: String?,
    val state: S,
    val check: CheckStatus?,
    /** Null when the object is not a problem, so there is nothing to handle. */
    val handling: Handling?,
    val markers: Set<Marker>,
) {
    val title: String get() = if (service == null) hostName else "$hostName / $service"

    /** Stable per object, so the list keeps its place across refreshes. */
    val key: String get() = if (service == null) hostName else "$hostName\u0000$service"

    val isHandled: Boolean get() = handling?.isHandled == true
}

/**
 * What the user has asked to see. An empty [states] means every state.
 *
 * Handled problems are shown unless switched off. These lists are for finding
 * something by name, and a host that vanished when it was acknowledged would
 * look like a host that is not monitored. The Problems tab is the opposite.
 */
data class BrowseFilter<S>(val query: String = "", val states: Set<S> = emptySet(), val showHandled: Boolean = true)

/** The rows to show, and the numbers on the chips above them. */
data class BrowseView<S>(
    val rows: List<BrowseRow<S>>,
    /** Per state, after the search and the handled switch but before the state chips: what a chip would show. */
    val counts: Map<S, Int>,
    /** Handled problems matching the search, whether or not they are shown. */
    val handledCount: Int,
)

fun <S> BrowseFilter<S>.apply(all: List<BrowseRow<S>>): BrowseView<S> {
    val needle = query.trim()
    val found = if (needle.isEmpty()) all else all.filter { it.title.contains(needle, ignoreCase = true) }
    val candidates = if (showHandled) found else found.filterNot { it.isHandled }
    return BrowseView(
        rows = if (states.isEmpty()) candidates else candidates.filter { it.state in states },
        counts = candidates.groupingBy { it.state }.eachCount(),
        handledCount = found.count { it.isHandled },
    )
}

/** Turns a poll into list rows, by name: these lists are for looking something up. */
object BrowseRows {
    fun hosts(snapshot: StatusSnapshot, report: ProblemReport): List<BrowseRow<HostState>> {
        val problems = report.hostProblems().associateBy { it.host.name }
        return snapshot.hosts
            .map { host ->
                BrowseRow(
                    hostName = host.name,
                    service = null,
                    state = host.state,
                    check = host.check,
                    handling = problems[host.name]?.handling,
                    markers = ProblemClassifier.markersOf(host.check),
                )
            }
            .sortedWith(ORDER)
    }

    /**
     * Every service's state comes from the cheap list; the details of the ones
     * in a problem state come from the report. The two are separate requests,
     * so a service can be in one and not the other for a moment: it is then
     * shown with its state alone.
     */
    fun services(snapshot: StatusSnapshot, report: ProblemReport): List<BrowseRow<ServiceState>> {
        val problems = report.serviceProblems().associateBy { it.service.hostName to it.service.description }
        return snapshot.serviceStates
            .map { entry ->
                // Details describe the state they were fetched for; if the state has moved on, drop them.
                val problem = problems[entry.hostName to entry.description]?.takeIf { it.service.state == entry.state }
                BrowseRow(
                    hostName = entry.hostName,
                    service = entry.description,
                    state = entry.state,
                    check = problem?.check,
                    handling = problem?.handling,
                    markers = problem?.markers.orEmpty(),
                )
            }
            .sortedWith(ORDER)
    }

    private fun ProblemReport.hostProblems(): List<HostProblem> = (unhandled + handled).filterIsInstance<HostProblem>()

    /** Including the ones rolled up under a down host, which the report does not list on their own. */
    private fun ProblemReport.serviceProblems(): List<ServiceProblem> = (unhandled + handled).flatMap { problem ->
        when (problem) {
            is HostProblem -> problem.services
            is ServiceProblem -> listOf(problem)
        }
    }

    private val ORDER: Comparator<BrowseRow<*>> =
        compareBy<BrowseRow<*>, String>(String.CASE_INSENSITIVE_ORDER) { it.hostName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.service.orEmpty() }
}
