// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.profile.ProfileCleanup

/**
 * The last poll of each profile, on disk, so the app has something to show
 * before the network answers and when there is no network at all.
 *
 * A poll replaces the previous one wholesale and in one transaction, so the
 * lists cannot accumulate and a killed process never leaves half a poll behind.
 * Per-object detail the user has opened is kept by [DetailCache].
 */
@Singleton
class StatusCache @Inject constructor(private val dao: StatusCacheDao) : ProfileCleanup {
    /** The latest poll, or null if this profile has never been polled successfully. */
    suspend fun loadPoll(profileId: Long): StatusSnapshot? {
        val meta = dao.pollMeta(profileId) ?: return null
        val details = dao.pollDetails(profileId)
        return StatusSnapshot(
            hosts = details.filter { it.isHost }.map { it.toHost() },
            serviceProblems = details.filterNot { it.isHost }.map { it.toService() },
            fetchedAt = Instant.ofEpochMilli(meta.lastSuccess),
            serviceStates = dao.serviceStates(profileId).map {
                ServiceStateEntry(it.hostName, it.description, serviceStateOf(it.state))
            },
        )
    }

    suspend fun savePoll(profileId: Long, snapshot: StatusSnapshot) {
        val fetchedAt = snapshot.fetchedAt.toEpochMilli()
        dao.replacePoll(
            profileId = profileId,
            states = snapshot.serviceStates.map {
                ServiceStateRow(profileId, it.hostName, it.description, it.state.name)
            },
            details = snapshot.hosts.map { it.toRow(profileId, fetchedAt) } +
                snapshot.serviceProblems.map { it.toRow(profileId, fetchedAt) },
            fetchedAt = fetchedAt,
        )
    }

    /** Called when a profile is deleted: its cache goes with it. */
    override suspend fun onProfileDeleted(profileId: Long) = dao.clear(profileId)

    /**
     * Removes cache for profiles that no longer exist. The two databases share no
     * foreign key, so a crash between deleting a profile and clearing its cache
     * would otherwise leave data behind for ever; this runs at startup as the backstop.
     */
    suspend fun sweepOrphans(existingProfileIds: Collection<Long>) {
        if (existingProfileIds.isEmpty()) dao.clearAll() else dao.sweep(existingProfileIds.toList())
    }
}
