// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.ui.browse.BrowseRow
import net.pgmac.nagwatch.ui.browse.BrowseRows

data class DetailUiState(
    val detail: ObjectDetail,
    /**
     * The state the last poll gave this object, for when there is no record to
     * show: a service that was fine and has never been opened has no details
     * anywhere until Nagios answers.
     */
    val polledState: Enum<*>? = null,
    /** For a host: its services, worst first. Empty for a service. */
    val services: List<BrowseRow<ServiceState>> = emptyList(),
    /** For a service: the host it is on, if the last poll knew it. */
    val host: BrowseRow<HostState>? = null,
    val now: Instant = Instant.EPOCH,
) {
    val ref: ObjectRef get() = detail.ref

    val title: String get() = ref.description?.let { "${ref.hostName} / $it" } ?: ref.hostName

    /** The check behind the record, whichever kind it is. */
    val check: CheckStatus?
        get() = when (val record = detail.record) {
            is CachedDetail.Host -> record.status.check
            is CachedDetail.Service -> record.status.check
            null -> null
        }

    /** The record's state if there is one, otherwise what the last poll said. */
    val state: Enum<*>?
        get() = when (val record = detail.record) {
            is CachedDetail.Host -> record.status.state
            is CachedDetail.Service -> record.status.state
            null -> polledState
        }
}

/**
 * Joins what was fetched for this object with what the last poll knows about
 * it and its neighbours. A record the user has never opened still has a
 * useful stand-in when the poll carried its details: every host, and every
 * service in a problem state.
 */
fun detailUiState(detail: ObjectDetail, status: ProfileStatus?, now: Instant): DetailUiState {
    val snapshot = status?.snapshot
    val report = status?.report
    val ref = detail.ref
    val withStandIn = if (detail.record == null && snapshot != null) {
        detail.copy(record = snapshot.recordOf(ref))
    } else {
        detail
    }
    if (snapshot == null || report == null) return DetailUiState(withStandIn, now = now)
    return DetailUiState(
        detail = withStandIn,
        polledState = snapshot.stateOf(ref),
        services = if (ref.isHost) {
            BrowseRows.services(snapshot, report).filter {
                it.hostName == ref.hostName
            }
        } else {
            emptyList()
        },
        host = if (ref.isHost) null else BrowseRows.hosts(snapshot, report).firstOrNull { it.hostName == ref.hostName },
        now = now,
    )
}

private fun StatusSnapshot.recordOf(ref: ObjectRef): CachedDetail? = when {
    ref.isHost -> hosts.firstOrNull { it.name == ref.hostName }?.let { CachedDetail.Host(it, fetchedAt) }

    else -> serviceProblems.firstOrNull { it.hostName == ref.hostName && it.description == ref.description }
        // Only if it is still what the cheap list says: details describe the state they were fetched for.
        ?.takeIf { it.state == stateOf(ref) }
        ?.let { CachedDetail.Service(it, fetchedAt) }
}

private fun StatusSnapshot.stateOf(ref: ObjectRef): Enum<*>? = when {
    ref.isHost -> hosts.firstOrNull { it.name == ref.hostName }?.state
    else -> serviceStates.firstOrNull { it.hostName == ref.hostName && it.description == ref.description }?.state
}

/** A date and time, with the year: comments on a busy object go back years. */
fun formatWhen(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
    instant?.let { WHEN.format(it.atZone(zone)) }.orEmpty()

/** A length of time in its two most useful units: "45m", "2h 00m", "3d 4h". */
fun formatSpan(span: Duration?): String {
    if (span == null || span.isNegative) return ""
    val minutes = span.toMinutes()
    return when {
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        span.toHours() < HOURS_PER_DAY -> "%dh %02dm".format(Locale.ROOT, span.toHours(), minutes % MINUTES_PER_HOUR)
        else -> "${span.toDays()}d ${span.toHours() % HOURS_PER_DAY}h"
    }
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
