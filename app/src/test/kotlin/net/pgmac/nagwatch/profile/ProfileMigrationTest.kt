// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pgmac.nagwatch.profile.db.ProfileDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A profile saved by an earlier version of the app must still be there after an upgrade.
 * There is no destructive fallback, so a schema change without a working migration would
 * stop the app from opening its profiles at all.
 *
 * The old database is built from the schema that version exported (`app/schemas`), not from
 * anything written here, so the test cannot drift from what was really shipped.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class ProfileMigrationTest {
    private val file: File = context().getDatabasePath("migration-test.db")
    private var opened: ProfileDatabase? = null

    @After
    fun cleanUp() {
        opened?.close()
        file.delete()
    }

    @Test
    fun `a profile saved by version 1 survives the upgrade, with no date format yet`() = runBlocking {
        createVersion(1)

        val profiles = open(*ProfileDatabase.MIGRATIONS.toTypedArray()).profiles()

        val kept = profiles.get(1)
        assertNotNull(kept)
        assertEquals("Home", kept?.name)
        assertEquals("https://nagios.example.org/nagios", kept?.baseUrl)
        assertEquals("https://nagios.example.org/nagios/cgi-bin/", kept?.cgiBase)
        assertEquals("nagwatch", kept?.username)
        assertEquals("encrypted-password", kept?.encryptedPassword)
        assertEquals(true, kept?.allowCleartext)
        assertNull(kept?.dateFormat)

        profiles.setDateFormat(1, "EURO")
        assertEquals("EURO", profiles.get(1)?.dateFormat)
    }

    @Test
    fun `a profile saved by version 2 survives the upgrade, with notifications off and not yet offered`() =
        runBlocking {
            createVersion(2) { db -> db.execSQL("UPDATE profiles SET date_format = 'EURO'") }

            val profiles = open(*ProfileDatabase.MIGRATIONS.toTypedArray()).profiles()

            val kept = checkNotNull(profiles.get(1))
            assertEquals("Home", kept.name)
            assertEquals("encrypted-password", kept.encryptedPassword)
            assertEquals("EURO", kept.dateFormat)
            assertEquals("nobody is polled in the background who did not ask to be", false, kept.notify)
            assertEquals(15, kept.pollMinutes)
            assertEquals(true, kept.notifyUnhandledOnly)
            assertEquals(true, kept.notifyUnreachable)
            assertEquals("so the app may offer once", false, kept.notifyOffered)

            profiles.answerNotifyOffer(1, accepted = false)
            assertEquals(false to true, profiles.get(1)?.let { it.notify to it.notifyOffered })
            profiles.answerNotifyOffer(1, accepted = true)
            assertEquals(true to true, profiles.get(1)?.let { it.notify to it.notifyOffered })
        }

    @Test
    fun `without the migration the old database is refused, not wiped`() {
        createVersion(1)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { open().profiles().get(1) }
        }
    }

    @Test
    fun `a version 2 database is refused too without its own migration`() {
        createVersion(2)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { open(ProfileDatabase.MIGRATION_1_2).profiles().get(1) }
        }
    }

    @Test
    fun `the app opens its profiles with every migration there is`() {
        assertEquals(
            (1 until ProfileDatabase.VERSION).map { it to it + 1 },
            ProfileDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    private fun open(vararg migrations: Migration): ProfileDatabase =
        Room.databaseBuilder(context(), ProfileDatabase::class.java, file.name)
            .allowMainThreadQueries()
            .addMigrations(*migrations)
            .build()
            .also { opened = it }

    /** A database exactly as [version] of the app created it, holding one profile. */
    private fun createVersion(version: Int, then: (SQLiteDatabase) -> Unit = {}) {
        val schema = Json.parseToJsonElement(File(SCHEMAS, "$version.json").readText()).jsonObject
        val table = schema.getValue("database").jsonObject.getValue("entities").jsonArray.first().jsonObject
        val create = table.getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", table.getValue("tableName").jsonPrimitive.content)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(create)
            db.execSQL(
                "INSERT INTO profiles (id, name, base_url, cgi_base, username, password_enc, access_client_id, " +
                    "access_client_secret_enc, custom_headers, allow_cleartext) VALUES (1, 'Home', " +
                    "'https://nagios.example.org/nagios', 'https://nagios.example.org/nagios/cgi-bin/', " +
                    "'nagwatch', 'encrypted-password', NULL, NULL, '[]', 1)",
            )
            then(db)
            db.version = version
        }
    }

    private companion object {
        /** Relative to the module directory, which is where Gradle runs unit tests. */
        const val SCHEMAS = "schemas/net.pgmac.nagwatch.profile.db.ProfileDatabase"
    }
}
