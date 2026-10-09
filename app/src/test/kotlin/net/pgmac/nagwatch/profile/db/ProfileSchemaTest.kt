// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every database version's schema is exported and committed, so a migration
 * can be tested against what users actually have installed. This guards the
 * habit: it fails if a version is declared without its schema file.
 *
 * When version 2 arrives it needs a real migration test here
 * (`MigrationTestHelper`), not just another file.
 */
class ProfileSchemaTest {
    private val schemaDir = File("schemas/${ProfileDatabase::class.java.name}")

    @Test
    fun `every database version up to the current one has a committed schema`() {
        val missing = (1..ProfileDatabase.VERSION).filterNot { File(schemaDir, "$it.json").isFile }

        assertTrue("missing schema files for versions $missing in $schemaDir", missing.isEmpty())
    }

    @Test
    fun `version 1 stores secrets only in the encrypted columns`() {
        val schema = Json.parseToJsonElement(File(schemaDir, "1.json").readText()).jsonObject
        val table = schema.getValue("database").jsonObject.getValue("entities").jsonArray.single().jsonObject
        val columns = table.getValue("fields").jsonArray.map {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

        assertEquals("profiles", table.getValue("tableName").jsonPrimitive.content)
        assertEquals(
            listOf(
                "id", "name", "base_url", "cgi_base", "username", "password_enc",
                "access_client_id", "access_client_secret_enc", "custom_headers", "allow_cleartext",
            ),
            columns,
        )
    }
}
