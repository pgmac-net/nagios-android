// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import java.time.Duration
import java.time.Instant
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType

/*
 * Between the domain model and the cache's rows. A host is stored with an
 * empty description.
 */

internal fun HostStatus.toRow(profileId: Long, fetchedAt: Long) =
    detailRow(profileId, ObjectRef(name), state.name, check, fetchedAt)

internal fun ServiceStatus.toRow(profileId: Long, fetchedAt: Long) =
    detailRow(profileId, ObjectRef(hostName, description), state.name, check, fetchedAt)

private fun detailRow(profileId: Long, ref: ObjectRef, state: String, check: CheckStatus, fetchedAt: Long) = DetailRow(
    profileId = profileId,
    hostName = ref.hostName,
    description = ref.description.orEmpty(),
    isHost = ref.isHost,
    state = state,
    stateType = check.stateType.name,
    output = check.pluginOutput,
    longOutput = check.longOutput,
    perfData = check.perfData,
    currentAttempt = check.currentAttempt,
    maxAttempts = check.maxAttempts,
    lastCheck = check.lastCheck?.toEpochMilli(),
    nextCheck = check.nextCheck?.toEpochMilli(),
    lastStateChange = check.lastStateChange?.toEpochMilli(),
    acknowledged = check.acknowledged,
    downtimeDepth = check.downtimeDepth,
    checksEnabled = check.checksEnabled,
    notificationsEnabled = check.notificationsEnabled,
    activeCheck = check.activeCheck,
    flapping = check.flapping,
    detailsAvailable = check.detailsAvailable,
    fetchedAt = fetchedAt,
    inPoll = false,
    openedAt = null,
)

internal fun DetailRow.toHost() = HostStatus(hostName, hostStateOf(state), toCheck())

internal fun DetailRow.toService() = ServiceStatus(hostName, description, serviceStateOf(state), toCheck())

private fun DetailRow.toCheck() = CheckStatus(
    stateType = enumOr(stateType, StateType.HARD),
    pluginOutput = output,
    longOutput = longOutput,
    perfData = perfData,
    currentAttempt = currentAttempt,
    maxAttempts = maxAttempts,
    lastCheck = lastCheck?.let(Instant::ofEpochMilli),
    nextCheck = nextCheck?.let(Instant::ofEpochMilli),
    lastStateChange = lastStateChange?.let(Instant::ofEpochMilli),
    acknowledged = acknowledged,
    downtimeDepth = downtimeDepth,
    checksEnabled = checksEnabled,
    notificationsEnabled = notificationsEnabled,
    activeCheck = activeCheck,
    flapping = flapping,
    detailsAvailable = detailsAvailable,
)

internal fun Comment.toRow(profileId: Long, ref: ObjectRef) = CommentRow(
    profileId = profileId,
    hostName = ref.hostName,
    description = ref.description.orEmpty(),
    commentId = id,
    kind = kind.name,
    author = author,
    text = text,
    enteredAt = enteredAt?.toEpochMilli(),
    persistent = persistent,
    expiresAt = expiresAt?.toEpochMilli(),
)

internal fun CommentRow.toComment() = Comment(
    id = commentId,
    kind = enumOr(kind, Comment.Kind.OTHER),
    author = author,
    text = text,
    enteredAt = enteredAt?.let(Instant::ofEpochMilli),
    persistent = persistent,
    expiresAt = expiresAt?.let(Instant::ofEpochMilli),
)

internal fun Downtime.toRow(profileId: Long, ref: ObjectRef) = DowntimeRow(
    profileId = profileId,
    hostName = ref.hostName,
    description = ref.description.orEmpty(),
    downtimeId = id,
    author = author,
    comment = comment,
    start = start?.toEpochMilli(),
    end = end?.toEpochMilli(),
    fixed = fixed,
    durationSeconds = duration?.seconds,
    inEffect = inEffect,
)

internal fun DowntimeRow.toDowntime() = Downtime(
    id = downtimeId,
    author = author,
    comment = comment,
    start = start?.let(Instant::ofEpochMilli),
    end = end?.let(Instant::ofEpochMilli),
    fixed = fixed,
    duration = durationSeconds?.let(Duration::ofSeconds),
    inEffect = inEffect,
)

// A value this version does not know (written by a newer one, say) reads as a problem
// state rather than as fine, the same rule the parser follows.
internal fun hostStateOf(name: String) = enumOr(name, HostState.UNREACHABLE)

internal fun serviceStateOf(name: String) = enumOr(name, ServiceState.UNKNOWN)

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
