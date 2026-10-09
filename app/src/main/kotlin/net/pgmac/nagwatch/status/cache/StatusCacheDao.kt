// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** What must survive when a poll rewrites a record the user had opened. */
data class OpenedMark(
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "comments_fetched_at") val commentsFetchedAt: Long?,
    @ColumnInfo(name = "downtimes_fetched_at") val downtimesFetchedAt: Long?,
)

/**
 * Every write that touches more than one row is a single transaction, so a
 * crash or a killed process never leaves half a poll on disk.
 */
// One function per SQL statement is what a DAO is; the count is the schema's, not a design smell.
@Suppress("TooManyFunctions")
@Dao
abstract class StatusCacheDao {
    // --- reads -----------------------------------------------------------------------------

    @Query("SELECT * FROM poll_meta WHERE profile_id = :profileId")
    abstract suspend fun pollMeta(profileId: Long): PollMetaRow?

    @Query("SELECT * FROM service_state WHERE profile_id = :profileId ORDER BY host_name, description")
    abstract suspend fun serviceStates(profileId: Long): List<ServiceStateRow>

    @Query("SELECT * FROM object_detail WHERE profile_id = :profileId AND in_poll = 1 ORDER BY host_name, description")
    abstract suspend fun pollDetails(profileId: Long): List<DetailRow>

    @Query(
        "SELECT * FROM object_detail WHERE profile_id = :profileId AND host_name = :hostName " +
            "AND description = :description",
    )
    abstract suspend fun detail(profileId: Long, hostName: String, description: String): DetailRow?

    @Query(
        "SELECT * FROM comment WHERE profile_id = :profileId AND host_name = :hostName " +
            "AND description = :description " +
            "ORDER BY entered_at DESC, comment_id DESC",
    )
    abstract suspend fun comments(profileId: Long, hostName: String, description: String): List<CommentRow>

    @Query(
        "SELECT * FROM downtime WHERE profile_id = :profileId AND host_name = :hostName " +
            "AND description = :description " +
            "ORDER BY start_at, downtime_id",
    )
    abstract suspend fun downtimes(profileId: Long, hostName: String, description: String): List<DowntimeRow>

    @Query("SELECT COUNT(*) FROM object_detail WHERE profile_id = :profileId AND opened_at IS NOT NULL")
    abstract suspend fun openedCount(profileId: Long): Int

    // --- a poll ----------------------------------------------------------------------------

    /**
     * Replaces a profile's poll data in one transaction: the state list wholesale,
     * and the detail records that came with the poll. Records the user had opened
     * keep their place in the opened list.
     */
    @Transaction
    open suspend fun replacePoll(
        profileId: Long,
        states: List<ServiceStateRow>,
        details: List<DetailRow>,
        fetchedAt: Long,
    ) {
        val opened = openedMarks(profileId).associateBy { it.hostName to it.description }
        deleteServiceStates(profileId)
        insertServiceStates(states)
        clearInPoll(profileId)
        upsertDetails(
            details.map { row ->
                val mark = opened[row.hostName to row.description]
                row.copy(
                    inPoll = true,
                    openedAt = mark?.openedAt,
                    commentsFetchedAt = mark?.commentsFetchedAt,
                    downtimesFetchedAt = mark?.downtimesFetchedAt,
                )
            },
        )
        deleteUnreferencedDetails(profileId)
        deleteOrphanedAnnotations(profileId)
        upsertPollMeta(PollMetaRow(profileId, fetchedAt))
    }

    // --- an opened detail --------------------------------------------------------------------

    /**
     * Stores a record the user opened and keeps only the [keep] most recently
     * opened ones. A record dropped from that list stays if the latest poll
     * also holds it; otherwise it goes, with its comments and downtimes.
     */
    @Transaction
    open suspend fun saveOpened(row: DetailRow, openedAt: Long, keep: Int) {
        val existing = detail(row.profileId, row.hostName, row.description)
        upsertDetails(
            listOf(
                row.copy(
                    inPoll = existing?.inPoll ?: false,
                    openedAt = openedAt,
                    commentsFetchedAt = existing?.commentsFetchedAt,
                    downtimesFetchedAt = existing?.downtimesFetchedAt,
                ),
            ),
        )
        forgetOldestOpened(row.profileId, keep)
        deleteUnreferencedDetails(row.profileId)
        deleteOrphanedAnnotations(row.profileId)
    }

    @Transaction
    open suspend fun replaceComments(
        profileId: Long,
        hostName: String,
        description: String,
        rows: List<CommentRow>,
        fetchedAt: Long,
    ) {
        deleteComments(profileId, hostName, description)
        insertComments(rows)
        markCommentsFetched(profileId, hostName, description, fetchedAt)
    }

    @Transaction
    open suspend fun replaceDowntimes(
        profileId: Long,
        hostName: String,
        description: String,
        rows: List<DowntimeRow>,
        fetchedAt: Long,
    ) {
        deleteDowntimes(profileId, hostName, description)
        insertDowntimes(rows)
        markDowntimesFetched(profileId, hostName, description, fetchedAt)
    }

    // --- removal -----------------------------------------------------------------------------

    /** Everything held for one profile: used when the profile is deleted. */
    @Transaction
    open suspend fun clear(profileId: Long) {
        deleteServiceStates(profileId)
        deleteDetails(profileId)
        deleteAllComments(profileId)
        deleteAllDowntimes(profileId)
        deletePollMeta(profileId)
    }

    /** Removes data for profiles that no longer exist. [validIds] must be non-empty; see [clearAll]. */
    @Transaction
    open suspend fun sweep(validIds: List<Long>) {
        sweepServiceStates(validIds)
        sweepDetails(validIds)
        sweepComments(validIds)
        sweepDowntimes(validIds)
        sweepPollMeta(validIds)
    }

    @Transaction
    open suspend fun clearAll() {
        deleteEveryServiceState()
        deleteEveryDetail()
        deleteEveryComment()
        deleteEveryDowntime()
        deleteEveryPollMeta()
    }

    // --- building blocks -----------------------------------------------------------------------

    @Query(
        "SELECT host_name, description, opened_at, comments_fetched_at, downtimes_fetched_at FROM object_detail " +
            "WHERE profile_id = :profileId AND opened_at IS NOT NULL",
    )
    protected abstract suspend fun openedMarks(profileId: Long): List<OpenedMark>

    @Insert
    protected abstract suspend fun insertServiceStates(rows: List<ServiceStateRow>)

    @Upsert
    protected abstract suspend fun upsertDetails(rows: List<DetailRow>)

    @Insert
    protected abstract suspend fun insertComments(rows: List<CommentRow>)

    @Insert
    protected abstract suspend fun insertDowntimes(rows: List<DowntimeRow>)

    @Upsert
    protected abstract suspend fun upsertPollMeta(row: PollMetaRow)

    @Query("UPDATE object_detail SET in_poll = 0 WHERE profile_id = :profileId")
    protected abstract suspend fun clearInPoll(profileId: Long)

    @Query("DELETE FROM object_detail WHERE profile_id = :profileId AND in_poll = 0 AND opened_at IS NULL")
    protected abstract suspend fun deleteUnreferencedDetails(profileId: Long)

    /** Drops the opened mark from everything but the [keep] most recently opened records. */
    @Query(
        "UPDATE object_detail SET opened_at = NULL WHERE profile_id = :profileId AND opened_at IS NOT NULL " +
            "AND opened_at < (SELECT MIN(opened_at) FROM (SELECT opened_at FROM object_detail " +
            "WHERE profile_id = :profileId AND opened_at IS NOT NULL ORDER BY opened_at DESC LIMIT :keep))",
    )
    protected abstract suspend fun forgetOldestOpened(profileId: Long, keep: Int)

    /** Comments and downtimes are only kept for records the user opened. */
    @Query(
        "DELETE FROM comment WHERE profile_id = :profileId AND NOT EXISTS (SELECT 1 FROM object_detail d " +
            "WHERE d.profile_id = comment.profile_id AND d.host_name = comment.host_name " +
            "AND d.description = comment.description AND d.opened_at IS NOT NULL)",
    )
    protected abstract suspend fun deleteOrphanedComments(profileId: Long)

    @Query(
        "DELETE FROM downtime WHERE profile_id = :profileId AND NOT EXISTS (SELECT 1 FROM object_detail d " +
            "WHERE d.profile_id = downtime.profile_id AND d.host_name = downtime.host_name " +
            "AND d.description = downtime.description AND d.opened_at IS NOT NULL)",
    )
    protected abstract suspend fun deleteOrphanedDowntimes(profileId: Long)

    protected open suspend fun deleteOrphanedAnnotations(profileId: Long) {
        deleteOrphanedComments(profileId)
        deleteOrphanedDowntimes(profileId)
    }

    @Query(
        "UPDATE object_detail SET comments_fetched_at = :fetchedAt " +
            "WHERE profile_id = :profileId AND host_name = :hostName AND description = :description",
    )
    protected abstract suspend fun markCommentsFetched(
        profileId: Long,
        hostName: String,
        description: String,
        fetchedAt: Long,
    )

    @Query(
        "UPDATE object_detail SET downtimes_fetched_at = :fetchedAt " +
            "WHERE profile_id = :profileId AND host_name = :hostName AND description = :description",
    )
    protected abstract suspend fun markDowntimesFetched(
        profileId: Long,
        hostName: String,
        description: String,
        fetchedAt: Long,
    )

    @Query("DELETE FROM comment WHERE profile_id = :profileId AND host_name = :hostName AND description = :description")
    protected abstract suspend fun deleteComments(profileId: Long, hostName: String, description: String)

    @Query(
        "DELETE FROM downtime WHERE profile_id = :profileId AND host_name = :hostName AND description = :description",
    )
    protected abstract suspend fun deleteDowntimes(profileId: Long, hostName: String, description: String)

    @Query("DELETE FROM service_state WHERE profile_id = :profileId")
    protected abstract suspend fun deleteServiceStates(profileId: Long)

    @Query("DELETE FROM object_detail WHERE profile_id = :profileId")
    protected abstract suspend fun deleteDetails(profileId: Long)

    @Query("DELETE FROM comment WHERE profile_id = :profileId")
    protected abstract suspend fun deleteAllComments(profileId: Long)

    @Query("DELETE FROM downtime WHERE profile_id = :profileId")
    protected abstract suspend fun deleteAllDowntimes(profileId: Long)

    @Query("DELETE FROM poll_meta WHERE profile_id = :profileId")
    protected abstract suspend fun deletePollMeta(profileId: Long)

    @Query("DELETE FROM service_state WHERE profile_id NOT IN (:validIds)")
    protected abstract suspend fun sweepServiceStates(validIds: List<Long>)

    @Query("DELETE FROM object_detail WHERE profile_id NOT IN (:validIds)")
    protected abstract suspend fun sweepDetails(validIds: List<Long>)

    @Query("DELETE FROM comment WHERE profile_id NOT IN (:validIds)")
    protected abstract suspend fun sweepComments(validIds: List<Long>)

    @Query("DELETE FROM downtime WHERE profile_id NOT IN (:validIds)")
    protected abstract suspend fun sweepDowntimes(validIds: List<Long>)

    @Query("DELETE FROM poll_meta WHERE profile_id NOT IN (:validIds)")
    protected abstract suspend fun sweepPollMeta(validIds: List<Long>)

    @Query("DELETE FROM service_state")
    protected abstract suspend fun deleteEveryServiceState()

    @Query("DELETE FROM object_detail")
    protected abstract suspend fun deleteEveryDetail()

    @Query("DELETE FROM comment")
    protected abstract suspend fun deleteEveryComment()

    @Query("DELETE FROM downtime")
    protected abstract suspend fun deleteEveryDowntime()

    @Query("DELETE FROM poll_meta")
    protected abstract suspend fun deleteEveryPollMeta()
}
