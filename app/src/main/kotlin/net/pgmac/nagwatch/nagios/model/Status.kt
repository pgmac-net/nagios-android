// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.model

import java.time.Instant

enum class HostState {
    UP,
    DOWN,
    UNREACHABLE,
    PENDING,
    ;

    val isProblem: Boolean get() = this == DOWN || this == UNREACHABLE
}

enum class ServiceState {
    OK,
    WARNING,
    CRITICAL,
    UNKNOWN,
    PENDING,
    ;

    val isProblem: Boolean get() = this == WARNING || this == CRITICAL || this == UNKNOWN
}

/** SOFT: failing but still being retried, not yet notified. HARD: confirmed. */
enum class StateType { SOFT, HARD }

/**
 * What both hosts and services report about their current check.
 *
 * When [detailsAvailable] is false only the name and state are real: Nagios
 * could not serialise the full record (see `ResilientListFetcher`), so every
 * other field holds a neutral default and must not be trusted.
 */
data class CheckStatus(
    val stateType: StateType = StateType.HARD,
    val pluginOutput: String = "",
    val currentAttempt: Int = 0,
    val maxAttempts: Int = 0,
    val lastCheck: Instant? = null,
    val lastStateChange: Instant? = null,
    val acknowledged: Boolean = false,
    val downtimeDepth: Int = 0,
    val checksEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val detailsAvailable: Boolean = true,
) {
    val inDowntime: Boolean get() = downtimeDepth > 0
}

data class HostStatus(val name: String, val state: HostState, val check: CheckStatus)

data class ServiceStatus(
    val hostName: String,
    val description: String,
    val state: ServiceState,
    val check: CheckStatus,
)

/** What `programstatus` says about the instance itself. */
data class ServerInfo(val version: String, val programStart: Instant?, val lastDataUpdate: Instant?)

/** One poll's worth of raw status: every host, and the services that are in a problem state. */
data class StatusSnapshot(
    val hosts: List<HostStatus>,
    val serviceProblems: List<ServiceStatus>,
    val fetchedAt: Instant,
) {
    /** Records Nagios could not serialise in full; shown as "details unavailable". */
    val degradedCount: Int
        get() = hosts.count { !it.check.detailsAvailable } + serviceProblems.count { !it.check.detailsAvailable }
}
