// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import android.content.res.AssetManager
import androidx.annotation.RawRes
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import net.pgmac.nagwatch.R

/** One library in the APK, as the build's licence check (Licensee) recorded it. */
data class Library(
    val coordinates: String,
    val version: String,
    val name: String,
    /** SPDX id of its licence, or null if Licensee could not name one. */
    val licenceId: String?,
    val licenceName: String,
)

/**
 * The libraries in the APK. The list is the build's own report, bundled as an asset, so it is what
 * shipped and nothing else. If it is missing or unreadable the answer is "none": About still works.
 */
object Libraries {
    const val ASSET = "app/cash/licensee/artifacts.json"

    fun load(assets: AssetManager): List<Library> = try {
        assets.open(ASSET).use { parse(it.readBytes().decodeToString()) }
    } catch (_: IOException) {
        emptyList()
    }

    fun parse(text: String): List<Library> = try {
        Json.parseToJsonElement(text).jsonArray.mapNotNull { it.toLibrary() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        // Valid JSON of the wrong shape: not an array.
        emptyList()
    }

    private fun JsonElement.toLibrary(): Library? {
        val entry = this as? JsonObject ?: return null
        val group = entry.string("groupId") ?: return null
        val artifact = entry.string("artifactId") ?: return null
        val known = (entry["spdxLicenses"] as? JsonArray)?.firstOrNull() as? JsonObject
        val unknown = (entry["unknownLicenses"] as? JsonArray)?.firstOrNull() as? JsonObject
        val licence = known ?: unknown
        return Library(
            coordinates = "$group:$artifact",
            version = entry.string("version").orEmpty(),
            name = entry.string("name")?.takeIf { it.isNotBlank() } ?: artifact,
            licenceId = known?.string("identifier"),
            licenceName = licence?.string("name") ?: licence?.string("identifier").orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** The full text of every licence the app ships under or shows, kept in the APK so it reads offline. */
object LicenceTexts {
    const val NAGWATCH = "GPL-3.0-or-later"

    private val raw = mapOf(
        "Apache-2.0" to R.raw.license_apache_2_0,
        "BSD-3-Clause" to R.raw.license_bsd_3_clause,
        NAGWATCH to R.raw.license_gpl_3_0,
    )

    @RawRes
    fun resourceFor(spdxId: String): Int? = raw[spdxId]
}
