// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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

        val profiles = open(migrate = true).profiles()

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
    fun `without the migration the old database is refused, not wiped`() {
        createVersion(1)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { open(migrate = false).profiles().get(1) }
        }
    }

    private fun open(migrate: Boolean): ProfileDatabase {
        val builder = Room.databaseBuilder(context(), ProfileDatabase::class.java, file.name).allowMainThreadQueries()
        if (migrate) builder.addMigrations(ProfileDatabase.MIGRATION_1_2)
        return builder.build().also { opened = it }
    }

    /** A database exactly as [version] of the app created it, holding one profile. */
    private fun createVersion(version: Int) {
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
            db.version = version
        }
    }

    private companion object {
        /** Relative to the module directory, which is where Gradle runs unit tests. */
        const val SCHEMAS = "schemas/net.pgmac.nagwatch.profile.db.ProfileDatabase"
    }
}
