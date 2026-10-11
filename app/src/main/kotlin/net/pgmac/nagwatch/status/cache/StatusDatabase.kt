// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/*
 * The status cache. Everything here can be fetched again from Nagios, which is
 * why this is a separate database from the profiles and why its migrations are
 * allowed to be destructive (docs/adr/0005).
 *
 * A host is stored with an empty `description`; a service with its own.
 */

/** Every service's name and state, replaced wholesale by each poll. */
@Entity(tableName = "service_state", primaryKeys = ["profile_id", "host_name", "description"])
data class ServiceStateRow(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    val state: String,
)

/**
 * A full record for a host or service. A row is here for one or both of two reasons:
 *
 * - [inPoll]: it came from the latest poll (every host, and services in a problem state);
 * - [openedAt]: the user opened its detail screen, so it is kept to show again, offline.
 *
 * A row that is neither is deleted.
 */
@Entity(tableName = "object_detail", primaryKeys = ["profile_id", "host_name", "description"])
data class DetailRow(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    @ColumnInfo(name = "is_host") val isHost: Boolean,
    val state: String,
    @ColumnInfo(name = "state_type") val stateType: String,
    val output: String,
    @ColumnInfo(name = "long_output") val longOutput: String,
    @ColumnInfo(name = "perf_data") val perfData: String,
    @ColumnInfo(name = "current_attempt") val currentAttempt: Int,
    @ColumnInfo(name = "max_attempts") val maxAttempts: Int,
    @ColumnInfo(name = "last_check") val lastCheck: Long?,
    @ColumnInfo(name = "next_check") val nextCheck: Long?,
    @ColumnInfo(name = "last_state_change") val lastStateChange: Long?,
    val acknowledged: Boolean,
    @ColumnInfo(name = "downtime_depth") val downtimeDepth: Int,
    @ColumnInfo(name = "checks_enabled") val checksEnabled: Boolean,
    @ColumnInfo(name = "notifications_enabled") val notificationsEnabled: Boolean,
    @ColumnInfo(name = "active_check") val activeCheck: Boolean,
    val flapping: Boolean,
    @ColumnInfo(name = "details_available") val detailsAvailable: Boolean,
    /** When this record was fetched from Nagios, epoch milliseconds. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "in_poll") val inPoll: Boolean,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    /** When comments / downtimes were last fetched successfully; null if never. */
    @ColumnInfo(name = "comments_fetched_at") val commentsFetchedAt: Long? = null,
    @ColumnInfo(name = "downtimes_fetched_at") val downtimesFetchedAt: Long? = null,
)

@Entity(tableName = "comment", primaryKeys = ["profile_id", "host_name", "description", "comment_id"])
data class CommentRow(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    @ColumnInfo(name = "comment_id") val commentId: Long,
    val kind: String,
    val author: String,
    val text: String,
    @ColumnInfo(name = "entered_at") val enteredAt: Long?,
    val persistent: Boolean,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
)

@Entity(tableName = "downtime", primaryKeys = ["profile_id", "host_name", "description", "downtime_id"])
data class DowntimeRow(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    @ColumnInfo(name = "downtime_id") val downtimeId: Long,
    val author: String,
    val comment: String,
    @ColumnInfo(name = "start_at") val start: Long?,
    @ColumnInfo(name = "end_at") val end: Long?,
    val fixed: Boolean,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long?,
    @ColumnInfo(name = "in_effect") val inEffect: Boolean,
)

/** When a profile was last polled successfully. */
@Entity(tableName = "poll_meta")
data class PollMetaRow(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "last_success") val lastSuccess: Long,
)

/**
 * A problem the person has been told about, or that was there at the first poll: what stops
 * the app announcing it again at every poll. A host has an empty `description`.
 */
@Entity(tableName = "announced", primaryKeys = ["profile_id", "host_name", "description"])
data class AnnouncedRow(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "host_name") val hostName: String,
    val description: String,
    /** A `Severity` name. */
    val severity: String,
    /** True if it has a notification of its own; false if it was only ever part of a summary. */
    val individually: Boolean,
)

/** How announcing stands for a profile: whether its first poll is done, and any outage in progress. */
@Entity(tableName = "announce_meta")
data class AnnounceMetaRow(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: Long,
    /** False until one poll has succeeded: the first that does is summarised, not itemised. */
    val initialised: Boolean,
    val failures: Int,
    /** An `OutageCause` name, for the outage the person has been told about; null if none. */
    val outage: String?,
)

/**
 * Unlike the profiles database, this one is built with a destructive fallback:
 * a schema change wipes the cache and the next refresh refills it. Nothing here
 * is the only copy of anything.
 */
@Database(
    entities = [
        ServiceStateRow::class,
        DetailRow::class,
        CommentRow::class,
        DowntimeRow::class,
        PollMetaRow::class,
        AnnouncedRow::class,
        AnnounceMetaRow::class,
    ],
    version = StatusDatabase.VERSION,
    exportSchema = true,
)
abstract class StatusDatabase : RoomDatabase() {
    abstract fun cache(): StatusCacheDao

    companion object {
        const val FILE_NAME = "status.db"
        const val VERSION = 2
    }
}
