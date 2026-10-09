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
)

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
        const val VERSION = 1
    }
}
