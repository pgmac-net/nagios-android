// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.notify.AnnouncedState
import net.pgmac.nagwatch.notify.Known
import net.pgmac.nagwatch.notify.OutageCause

/**
 * What the app remembers having announced, for each profile, across restarts.
 *
 * It lives in the status cache on purpose (docs/adr/0008). It can be rebuilt: lose it, and
 * the next poll is treated as a first poll and summarised. That is a small price, and the
 * alternative is a strict migration for data that describes a moment that has passed.
 * Deleting a profile removes it with the rest of that profile's cache.
 */
@Singleton
class AnnouncedStore @Inject constructor(private val dao: StatusCacheDao) {
    /** Nothing stored is "never polled": the state a profile starts in. */
    suspend fun load(profileId: Long): AnnouncedState {
        val meta = dao.announceMeta(profileId) ?: return AnnouncedState()
        val known = dao.announced(profileId).mapNotNull { row ->
            // A name this version does not know is dropped: at worst that one is announced again.
            val severity = Severity.entries.firstOrNull { it.name == row.severity } ?: return@mapNotNull null
            ObjectRef(row.hostName, row.description.ifEmpty { null }) to Known(severity, row.individually)
        }
        return AnnouncedState(
            initialised = meta.initialised,
            known = known.toMap(),
            failures = meta.failures,
            outage = meta.outage?.let { name -> OutageCause.entries.firstOrNull { it.name == name } },
        )
    }

    /**
     * Forgets everything announced for a profile, so that its next poll is a first poll.
     * For a profile whose notifications are switched off: switching them back on starts afresh
     * (docs/adr/0008).
     */
    suspend fun forget(profileId: Long) = dao.forgetAnnounced(profileId)

    suspend fun save(profileId: Long, state: AnnouncedState) = dao.replaceAnnounced(
        profileId = profileId,
        rows = state.known.map { (ref, known) ->
            AnnouncedRow(profileId, ref.hostName, ref.description.orEmpty(), known.severity.name, known.individually)
        },
        meta = AnnounceMetaRow(profileId, state.initialised, state.failures, state.outage?.name),
    )
}
