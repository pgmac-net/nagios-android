// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.model

import java.time.Instant

/** Why a problem does or does not need attention. See CONTEXT.md, "Unhandled problem". */
enum class Handling {
    UNHANDLED,
    ACKNOWLEDGED,
    IN_DOWNTIME,

    /** A service whose host is itself down: the host problem stands in for it. */
    HOST_DOWN,
    ;

    val isHandled: Boolean get() = this != UNHANDLED
}

/** Things worth showing beside a problem that do not change whether it counts. */
enum class Marker {
    /** Still being retried; Nagios has not notified yet. */
    SOFT,
    CHECKS_DISABLED,
    NOTIFICATIONS_DISABLED,

    /** Nagios could not serialise the record; only name and state are known. */
    DETAILS_UNAVAILABLE,
}

/** Display order: most urgent first. */
enum class Severity { HOST_DOWN, HOST_UNREACHABLE, CRITICAL, WARNING, UNKNOWN }

sealed interface Problem {
    val severity: Severity
    val handling: Handling
    val markers: Set<Marker>
    val check: CheckStatus
    val since: Instant? get() = check.lastStateChange
}

data class HostProblem(
    val host: HostStatus,
    override val handling: Handling,
    override val markers: Set<Marker>,
    /** This host's service problems, rolled up here instead of listed on their own. */
    val services: List<ServiceProblem>,
) : Problem {
    override val severity: Severity
        get() = if (host.state == HostState.DOWN) Severity.HOST_DOWN else Severity.HOST_UNREACHABLE
    override val check: CheckStatus get() = host.check
}

data class ServiceProblem(
    val service: ServiceStatus,
    override val handling: Handling,
    override val markers: Set<Marker>,
) : Problem {
    override val severity: Severity
        get() = when (service.state) {
            ServiceState.CRITICAL -> Severity.CRITICAL
            ServiceState.WARNING -> Severity.WARNING
            else -> Severity.UNKNOWN
        }
    override val check: CheckStatus get() = service.check
}

/** Unhandled problems by kind: the headline numbers for chips, badge and widget. */
data class ProblemCounts(val hostsDown: Int, val critical: Int, val warning: Int, val unknown: Int) {
    val total: Int get() = hostsDown + critical + warning + unknown
}

/**
 * One poll, sorted and split for display.
 *
 * Services on a down host appear only inside that host's [HostProblem.services],
 * in whichever list the host itself is in.
 */
data class ProblemReport(
    val unhandled: List<Problem>,
    val handled: List<Problem>,
    val counts: ProblemCounts,
    val degradedCount: Int,
    val fetchedAt: Instant,
)
