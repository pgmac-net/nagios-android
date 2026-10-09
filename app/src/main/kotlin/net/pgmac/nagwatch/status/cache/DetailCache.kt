// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceStatus

/** A host or service record from the cache, with when it was fetched. */
sealed interface CachedDetail {
    val fetchedAt: Instant

    data class Host(val status: HostStatus, override val fetchedAt: Instant) : CachedDetail

    data class Service(val status: ServiceStatus, override val fetchedAt: Instant) : CachedDetail
}

/** Comments or downtimes from the cache. [fetchedAt] is null if they were never fetched for this object. */
data class CachedAnnotations<T>(val items: List<T>, val fetchedAt: Instant?)

/**
 * Detail the user has opened, kept so it can be shown again at once and offline.
 *
 * Bounded per profile, because how much Nagios sends is Nagios' decision:
 * records for the [MAX_OPENED] most recently opened objects, and at most
 * [MAX_ANNOTATIONS] comments and downtimes each. A record dropped from the
 * opened list takes its comments and downtimes with it.
 */
@Singleton
class DetailCache @Inject constructor(private val dao: StatusCacheDao, private val clock: Clock) {
    suspend fun load(profileId: Long, ref: ObjectRef): CachedDetail? =
        dao.detail(profileId, ref.hostName, ref.description.orEmpty())?.let { row ->
            val fetchedAt = Instant.ofEpochMilli(row.fetchedAt)
            when {
                row.isHost -> CachedDetail.Host(row.toHost(), fetchedAt)
                else -> CachedDetail.Service(row.toService(), fetchedAt)
            }
        }

    /** Remembers a record the user opened. */
    suspend fun saveOpened(profileId: Long, host: HostStatus) {
        val now = clock.millis()
        dao.saveOpened(host.toRow(profileId, now), openedAt = now, keep = MAX_OPENED)
    }

    suspend fun saveOpened(profileId: Long, service: ServiceStatus) {
        val now = clock.millis()
        dao.saveOpened(service.toRow(profileId, now), openedAt = now, keep = MAX_OPENED)
    }

    suspend fun loadComments(profileId: Long, ref: ObjectRef): CachedAnnotations<Comment> = CachedAnnotations(
        items = dao.comments(profileId, ref.hostName, ref.description.orEmpty()).map { it.toComment() },
        fetchedAt = dao.detail(profileId, ref.hostName, ref.description.orEmpty())?.commentsFetchedAt
            ?.let(Instant::ofEpochMilli),
    )

    /** Keeps only the newest [MAX_ANNOTATIONS]. The record itself must already be saved as opened. */
    suspend fun saveComments(profileId: Long, ref: ObjectRef, comments: List<Comment>) {
        val rows = comments.distinctBy { it.id }
            .sortedByDescending { it.enteredAt }
            .take(MAX_ANNOTATIONS)
            .map { it.toRow(profileId, ref) }
        dao.replaceComments(profileId, ref.hostName, ref.description.orEmpty(), rows, clock.millis())
    }

    suspend fun loadDowntimes(profileId: Long, ref: ObjectRef): CachedAnnotations<Downtime> = CachedAnnotations(
        items = dao.downtimes(profileId, ref.hostName, ref.description.orEmpty()).map { it.toDowntime() },
        fetchedAt = dao.detail(profileId, ref.hostName, ref.description.orEmpty())?.downtimesFetchedAt
            ?.let(Instant::ofEpochMilli),
    )

    suspend fun saveDowntimes(profileId: Long, ref: ObjectRef, downtimes: List<Downtime>) {
        val rows = downtimes.distinctBy { it.id }.take(MAX_ANNOTATIONS).map { it.toRow(profileId, ref) }
        dao.replaceDowntimes(profileId, ref.hostName, ref.description.orEmpty(), rows, clock.millis())
    }

    companion object {
        const val MAX_OPENED = 200
        const val MAX_ANNOTATIONS = 20
    }
}
