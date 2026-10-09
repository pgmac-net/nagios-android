// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Instant
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot

/**
 * Decides which problems are unhandled. Nagios' JSON CGI cannot filter on this
 * (it has no `serviceprops` / `hostprops`), so it is computed here.
 *
 * A problem is **unhandled** unless one of these holds:
 *
 * - it is acknowledged;
 * - it is in scheduled downtime, its own or (for a service) its host's;
 * - it is a service whose host is DOWN or UNREACHABLE. The host problem stands
 *   in for it and the service is rolled up under that host.
 *
 * SOFT states, disabled checks and disabled notifications do **not** make a
 * problem handled. They are flagged with a [Marker] instead: this is a view
 * for looking, and hiding a failing check because it has not paged anyone yet
 * would delay noticing it. A degraded record cannot be shown to be handled,
 * so it counts as unhandled.
 */
object ProblemClassifier {
    fun classify(snapshot: StatusSnapshot): ProblemReport {
        val hostsByName = snapshot.hosts.associateBy(HostStatus::name)
        val serviceProblems = snapshot.serviceProblems
            .filter { it.state.isProblem }
            .map { service -> classify(service, hostsByName[service.hostName]) }
        val (rolledUp, standalone) = serviceProblems.partition { it.handling == Handling.HOST_DOWN }
        val rolledUpByHost = rolledUp.groupBy { it.service.hostName }

        val hostProblems = snapshot.hosts
            .filter { it.state.isProblem }
            .map { host ->
                HostProblem(
                    host = host,
                    handling = handlingOf(host.check, hostInDowntime = false),
                    markers = markersOf(host.check),
                    services = rolledUpByHost[host.name].orEmpty().sortedWith(ORDER),
                )
            }

        val all: List<Problem> = (hostProblems + standalone).sortedWith(ORDER)
        val (handled, unhandled) = all.partition { it.handling.isHandled }
        return ProblemReport(
            unhandled = unhandled,
            handled = handled,
            counts = ProblemCounts(
                hostsDown = unhandled.count { it is HostProblem },
                critical = unhandled.count { it.severity == Severity.CRITICAL },
                warning = unhandled.count { it.severity == Severity.WARNING },
                unknown = unhandled.count { it.severity == Severity.UNKNOWN },
            ),
            degradedCount = snapshot.degradedCount,
            fetchedAt = snapshot.fetchedAt,
        )
    }

    private fun classify(service: ServiceStatus, host: HostStatus?): ServiceProblem {
        // A host missing from the list is treated as up: the service is then judged on
        // its own, which errs towards showing it.
        val handling = if (host?.state?.isProblem == true) {
            Handling.HOST_DOWN
        } else {
            handlingOf(service.check, hostInDowntime = host?.check?.inDowntime == true)
        }
        return ServiceProblem(service, handling, markersOf(service.check))
    }

    private fun handlingOf(check: CheckStatus, hostInDowntime: Boolean): Handling = when {
        !check.detailsAvailable -> if (hostInDowntime) Handling.IN_DOWNTIME else Handling.UNHANDLED
        check.acknowledged -> Handling.ACKNOWLEDGED
        check.inDowntime || hostInDowntime -> Handling.IN_DOWNTIME
        else -> Handling.UNHANDLED
    }

    private fun markersOf(check: CheckStatus): Set<Marker> = buildSet {
        if (!check.detailsAvailable) {
            add(Marker.DETAILS_UNAVAILABLE)
            return@buildSet
        }
        if (check.stateType == StateType.SOFT) add(Marker.SOFT)
        if (!check.checksEnabled) add(Marker.CHECKS_DISABLED)
        if (!check.notificationsEnabled) add(Marker.NOTIFICATIONS_DISABLED)
    }

    /** Most severe first; within a severity, confirmed before soft, then most recent change first. */
    private val ORDER: Comparator<Problem> = compareBy<Problem> { it.severity }
        .thenBy { Marker.SOFT in it.markers }
        .thenByDescending { it.since ?: Instant.MIN }
        .thenBy { it.sortName }

    private val Problem.sortName: String
        get() = when (this) {
            is HostProblem -> host.name
            is ServiceProblem -> "${service.hostName}\u0000${service.description}"
        }
}
