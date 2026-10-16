// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A profile as stored. Secret columns hold [net.pgmac.nagwatch.profile.SecretCipher]
 * output, never plaintext; null means "not set".
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    /** The CGI directory found by the last successful connect, to skip the search next time. */
    @ColumnInfo(name = "cgi_base") val cgiBase: String?,
    val username: String,
    @ColumnInfo(name = "password_enc") val encryptedPassword: String?,
    @ColumnInfo(name = "access_client_id") val accessClientId: String?,
    @ColumnInfo(name = "access_client_secret_enc") val encryptedAccessClientSecret: String?,
    /** JSON array of `{"name": ..., "value_enc": ...}`; values are secrets, names are not. */
    @ColumnInfo(name = "custom_headers") val customHeaders: String,
    @ColumnInfo(name = "allow_cleartext") val allowCleartext: Boolean,
    /**
     * How this server writes dates, as last seen (a `ServerDateFormat` name), for the day its
     * own form cannot show it. Null until a downtime has been scheduled. See docs/adr/0006.
     */
    @ColumnInfo(name = "date_format") val dateFormat: String? = null,
    /** Background polling and notifications for this profile. Off until the person switches it on. */
    @ColumnInfo(name = "notify", defaultValue = "0") val notify: Boolean = false,
    @ColumnInfo(name = "poll_minutes", defaultValue = "15") val pollMinutes: Int = DEFAULT_POLL_MINUTES,
    @ColumnInfo(name = "notify_unhandled_only", defaultValue = "1") val notifyUnhandledOnly: Boolean = true,
    @ColumnInfo(name = "notify_unreachable", defaultValue = "1") val notifyUnreachable: Boolean = true,
    /** The app has offered notifications for this profile, so it does not offer again. */
    @ColumnInfo(name = "notify_offered", defaultValue = "0") val notifyOffered: Boolean = false,
    /** Whether nagiosgraph's graphs are asked for. On until the person switches it off. */
    @ColumnInfo(name = "graphs", defaultValue = "1") val graphs: Boolean = true,
    /** A path on this profile's server, or empty for where Nagios' own CGIs are. Never a URL. */
    @ColumnInfo(name = "graph_path", defaultValue = "''") val graphPath: String = "",
    /** The comment a quick acknowledgement sends, or empty for the app's default. */
    @ColumnInfo(name = "ack_comment", defaultValue = "''") val ackComment: String = "",
    /** Down and critical are heard through silent mode and Do Not Disturb. Off until switched on. */
    @ColumnInfo(name = "loud_alerts", defaultValue = "0") val loudAlerts: Boolean = false,
) {
    companion object {
        const val DEFAULT_POLL_MINUTES = 15
    }
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("UPDATE profiles SET cgi_base = :cgiBase WHERE id = :id")
    suspend fun setCgiBase(id: Long, cgiBase: String?)

    @Query("UPDATE profiles SET date_format = :dateFormat WHERE id = :id")
    suspend fun setDateFormat(id: Long, dateFormat: String?)

    /** One statement, so the offer is never recorded as made with its answer still to be written. */
    @Query(
        "UPDATE profiles SET notify_offered = 1, notify = CASE WHEN :accepted THEN 1 ELSE notify END WHERE id = :id",
    )
    suspend fun answerNotifyOffer(id: Long, accepted: Boolean)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Schema versions are exported to `app/schemas` and committed. Raising the
 * version without a migration and a migration test is a data-loss bug:
 * there is no destructive fallback configured, on purpose.
 */
@Database(entities = [ProfileEntity::class], version = ProfileDatabase.VERSION, exportSchema = true)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao

    companion object {
        const val FILE_NAME = "profiles.db"
        const val VERSION = 6

        /** Adds the remembered date format. Existing profiles have none, which is the truth. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN date_format TEXT")
            }
        }

        /**
         * Adds the notification settings. Existing profiles get notifications off, which is what
         * they had, and have not been offered them, so the app will offer once.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN notify INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN poll_minutes INTEGER NOT NULL DEFAULT 15")
                db.execSQL("ALTER TABLE profiles ADD COLUMN notify_unhandled_only INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN notify_unreachable INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN notify_offered INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the graph settings. Existing profiles get graphs on, like new ones, and no path
         * of their own: the app looks where it already found Nagios' CGIs.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN graphs INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN graph_path TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds the comment a quick acknowledgement sends. Existing profiles get none of their
         * own, like new ones, and so send the app's default.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN ack_comment TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds loud alerts. Off for existing profiles, like new ones: nothing gets louder by itself. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN loud_alerts INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Every migration, in order. One list, so the app and the tests cannot open the database differently. */
        val MIGRATIONS: List<Migration> =
            listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    }
}
