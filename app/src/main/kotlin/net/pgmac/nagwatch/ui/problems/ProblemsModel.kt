// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import java.time.Duration
import java.time.Instant
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus

/** The count chips along the top; tapping one filters the list to it. */
enum class ProblemKind(val severities: Set<Severity>) {
    HOSTS(setOf(Severity.HOST_DOWN, Severity.HOST_UNREACHABLE)),
    CRITICAL(setOf(Severity.CRITICAL)),
    WARNING(setOf(Severity.WARNING)),
    UNKNOWN(setOf(Severity.UNKNOWN)),
}

data class ProblemsUiState(
    /** True until the first list of profiles has arrived. */
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val selected: Profile? = null,
    val status: ProfileStatus? = null,
    val filter: Set<ProblemKind> = emptySet(),
    val now: Instant = Instant.EPOCH,
) {
    /** Unhandled problems with the active filter applied. */
    val visibleUnhandled: List<Problem>
        get() = status?.report?.unhandled.orEmpty().filter(::matchesFilter)

    val visibleHandled: List<Problem>
        get() = status?.report?.handled.orEmpty().filter(::matchesFilter)

    private fun matchesFilter(problem: Problem): Boolean =
        filter.isEmpty() || filter.any { problem.severity in it.severities }
}

/**
 * The profile to show: the one the user picked if it still exists, otherwise
 * the first. A deleted profile must not leave the screen pointing at nothing.
 */
fun selectProfile(profiles: List<Profile>, requestedId: Long?): Profile? =
    profiles.firstOrNull { it.id == requestedId } ?: profiles.firstOrNull()

/** A problem's host and, for a service, its name: "web01" or "web01 / Disk /". */
fun Problem.title(): String = when (this) {
    is HostProblem -> host.name
    is ServiceProblem -> "${service.hostName} / ${service.description}"
}

/** What the plugin said; empty for a degraded record, where Nagios could not provide it. */
fun Problem.output(): String = check.pluginOutput.lineSequence().firstOrNull().orEmpty()

/**
 * How long ago something happened, in the two most useful units: "<1m", "42m",
 * "2h 04m", "3d 4h". A time in the future (clock skew between phone and
 * Nagios) reads as "<1m" rather than a negative.
 */
fun formatAge(since: Instant?, now: Instant): String {
    if (since == null) return ""
    val age = Duration.between(since, now)
    val minutes = age.toMinutes()
    return when {
        minutes < 1 -> "<1m"
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        age.toHours() < HOURS_PER_DAY -> "%dh %02dm".format(age.toHours(), minutes % MINUTES_PER_HOUR)
        else -> "${age.toDays()}d ${age.toHours() % HOURS_PER_DAY}h"
    }
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
