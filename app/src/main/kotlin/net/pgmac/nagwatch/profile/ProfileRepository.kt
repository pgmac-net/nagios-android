// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.profile.db.ProfileDao
import net.pgmac.nagwatch.profile.db.ProfileEntity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The outcome of turning a profile into something a request can be made with. */
sealed interface SettingsResult {
    /** Not a data class: [settings] holds decrypted secrets and must not be printed. */
    class Ready(val settings: ConnectionSettings) : SettingsResult

    data object NotFound : SettingsResult

    /**
     * A stored secret could not be decrypted: the Keystore key is gone (device
     * restore, some vendor bugs) or the value was altered. The profile is kept;
     * the user has to enter its secrets again.
     */
    data object CredentialsUnavailable : SettingsResult

    data object InvalidUrl : SettingsResult
}

/**
 * Profiles, with their secrets encrypted at rest. Plaintext secrets exist only
 * in a [ProfileDraft] on the way in and a [ConnectionSettings] on the way out.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val cipher: SecretCipher,
    private val json: Json,
    private val cleanups: Set<@JvmSuppressWildcards ProfileCleanup> = emptySet(),
) {
    fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { rows -> rows.map(::toProfile) }

    suspend fun get(id: Long): Profile? = dao.get(id)?.let(::toProfile)

    /** @return the profile's id. */
    suspend fun save(draft: ProfileDraft): Long {
        val entity = merge(draft, draft.id?.let { dao.get(it) })
        return if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            entity.id
        }
    }

    /** Deletes the profile, then everything else that was kept for it (its cached status). */
    suspend fun delete(id: Long) {
        dao.delete(id)
        cleanups.forEach { it.onProfileDeleted(id) }
    }

    suspend fun rememberCgiBase(id: Long, cgiBase: HttpUrl) = dao.setCgiBase(id, cgiBase.toString())

    suspend fun settingsFor(id: Long): SettingsResult = dao.get(id)?.let(::settings) ?: SettingsResult.NotFound

    /**
     * Settings for a draft that has not been saved, for "Test connection".
     * Secrets left as [SecretInput.Keep] are taken from the stored profile.
     */
    suspend fun settingsFor(draft: ProfileDraft): SettingsResult = settings(merge(draft, draft.id?.let { dao.get(it) }))

    private fun merge(draft: ProfileDraft, stored: ProfileEntity?): ProfileEntity {
        val baseUrl = draft.baseUrl.trim()
        val accessClientId = draft.accessClientId.trim().ifEmpty { null }
        val storedHeaders = stored?.let { headers(it.customHeaders) }.orEmpty()
        return ProfileEntity(
            id = stored?.id ?: 0,
            name = draft.name.trim(),
            baseUrl = baseUrl,
            // A remembered CGI directory belongs to the URL it was found under.
            cgiBase = stored?.cgiBase.takeIf { stored?.baseUrl == baseUrl },
            username = draft.username.trim(),
            encryptedPassword = resolve(draft.password, stored?.encryptedPassword),
            accessClientId = accessClientId,
            encryptedAccessClientSecret = accessClientId?.let {
                resolve(draft.accessClientSecret, stored?.encryptedAccessClientSecret)
            },
            customHeaders = buildJsonArray {
                draft.customHeaders.forEach { header ->
                    val name = header.name.trim()
                    val kept = storedHeaders.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                    resolve(header.value, kept)?.let { value ->
                        add(
                            buildJsonObject {
                                put(HEADER_NAME, name)
                                put(HEADER_VALUE, value)
                            },
                        )
                    }
                }
            }.toString(),
            allowCleartext = draft.allowCleartext,
        )
    }

    private fun resolve(input: SecretInput, stored: String?): String? = when (input) {
        SecretInput.Keep -> stored
        SecretInput.Clear -> null
        is SecretInput.Replace -> input.value.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
    }

    private fun settings(entity: ProfileEntity): SettingsResult {
        val baseUrl = entity.baseUrl.toHttpUrlOrNull() ?: return SettingsResult.InvalidUrl
        val password = entity.encryptedPassword?.let(cipher::decrypt)
        val accessSecret = entity.encryptedAccessClientSecret?.let(cipher::decrypt)
        val headers = headers(entity.customHeaders).map { (name, value) -> name to cipher.decrypt(value) }

        val unreadable = password == null ||
            (entity.encryptedAccessClientSecret != null && accessSecret == null) ||
            headers.any { it.second == null }
        return if (unreadable) {
            SettingsResult.CredentialsUnavailable
        } else {
            SettingsResult.Ready(
                ConnectionSettings(
                    baseUrl = baseUrl,
                    username = entity.username,
                    password = password.orEmpty(),
                    accessClientId = entity.accessClientId,
                    accessClientSecret = accessSecret,
                    customHeaders = headers.map { (name, value) -> name to value.orEmpty() },
                    allowCleartext = entity.allowCleartext,
                    cgiBase = entity.cgiBase?.toHttpUrlOrNull(),
                ),
            )
        }
    }

    private fun toProfile(entity: ProfileEntity) = Profile(
        id = entity.id,
        name = entity.name,
        baseUrl = entity.baseUrl,
        username = entity.username,
        hasPassword = entity.encryptedPassword != null,
        accessClientId = entity.accessClientId.orEmpty(),
        hasAccessClientSecret = entity.encryptedAccessClientSecret != null,
        customHeaderNames = headers(entity.customHeaders).map { it.first },
        allowCleartext = entity.allowCleartext,
    )

    /** Stored headers as (name, encrypted value). Anything malformed is dropped, not fatal. */
    private fun headers(stored: String): List<Pair<String, String>> {
        val array = try {
            json.parseToJsonElement(stored) as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        return array.orEmpty().mapNotNull { element ->
            val header = element as? JsonObject
            val name = (header?.get(HEADER_NAME) as? JsonPrimitive)?.content
            val value = (header?.get(HEADER_VALUE) as? JsonPrimitive)?.content
            if (name.isNullOrEmpty() || value.isNullOrEmpty()) null else name to value
        }
    }

    private companion object {
        const val HEADER_NAME = "name"
        const val HEADER_VALUE = "value_enc"
    }
}
