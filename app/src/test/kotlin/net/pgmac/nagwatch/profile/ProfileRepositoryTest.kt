// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.profile.SecretInput.Clear
import net.pgmac.nagwatch.profile.SecretInput.Keep
import net.pgmac.nagwatch.profile.SecretInput.Replace
import net.pgmac.nagwatch.profile.db.ProfileDatabase
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class ProfileRepositoryTest {
    private val database = inMemoryProfileDatabase()
    private val keys = softwareKeySource()
    private val repository = ProfileRepository(database.profiles(), SecretCipher(keys), Json)

    @After
    fun close() = database.close()

    @Test
    fun `a saved profile can be read back, without its secrets`() = runBlocking {
        val id = repository.save(draft())

        val profile = checkNotNull(repository.get(id))

        assertEquals("Home", profile.name)
        assertEquals("https://nagios.example.org/nagios", profile.baseUrl)
        assertEquals("nagwatch", profile.username)
        assertTrue(profile.hasPassword && profile.hasAccessClientSecret)
        assertEquals("id.access", profile.accessClientId)
        assertEquals(listOf("X-Proxy-Token"), profile.customHeaderNames)
        assertFalse("a profile is not allowed to print a secret", profile.toString().contains(PASSWORD))
    }

    @Test
    fun `no secret is stored in plaintext`() = runBlocking {
        repository.save(draft())

        val row = checkNotNull(database.profiles().get(1))
        val everything = row.toString()

        listOf(PASSWORD, ACCESS_SECRET, HEADER_VALUE).forEach { secret ->
            assertFalse("found $secret in the stored row", everything.contains(secret))
        }
        assertTrue(row.encryptedPassword.orEmpty().startsWith("v1:"))
    }

    @Test
    fun `no secret is in the database file`() = runBlocking {
        val file = File.createTempFile("profiles", ".db").apply { deleteOnExit() }
        val onDisk = Room.databaseBuilder(context(), ProfileDatabase::class.java, file.path).build()
        ProfileRepository(onDisk.profiles(), SecretCipher(keys), Json).save(draft())
        onDisk.close()

        val bytes = file.readBytes().toString(Charsets.ISO_8859_1) +
            File(file.path + "-wal").takeIf(File::exists)?.readBytes()?.toString(Charsets.ISO_8859_1).orEmpty()

        assertTrue("sanity: the profile really was written", bytes.contains("nagios.example.org"))
        listOf(PASSWORD, ACCESS_SECRET, HEADER_VALUE).forEach { secret ->
            assertFalse("found $secret in the database file", bytes.contains(secret))
        }
    }

    @Test
    fun `settings carry the decrypted secrets for a request`() = runBlocking {
        val id = repository.save(draft())

        val settings = ready(repository.settingsFor(id))

        assertEquals("https://nagios.example.org/nagios".toHttpUrl(), settings.baseUrl)
        assertEquals(PASSWORD, settings.password)
        assertEquals(ACCESS_SECRET, settings.accessClientSecret)
        assertEquals(listOf("X-Proxy-Token" to HEADER_VALUE), settings.customHeaders)
        assertFalse(settings.allowCleartext)
    }

    @Test
    fun `keep leaves stored secrets alone, replace overwrites, clear removes`() = runBlocking {
        val id = repository.save(draft())

        repository.save(draft(id = id, password = Keep, accessClientSecret = Replace("new-access"), headerValue = Keep))
        val afterKeep = ready(repository.settingsFor(id))
        assertEquals(PASSWORD, afterKeep.password)
        assertEquals("new-access", afterKeep.accessClientSecret)
        assertEquals(listOf("X-Proxy-Token" to HEADER_VALUE), afterKeep.customHeaders)

        repository.save(draft(id = id, password = Clear, accessClientSecret = Keep, headerValue = Keep))
        assertFalse(checkNotNull(repository.get(id)).hasPassword)
    }

    @Test
    fun `removing the Access client ID removes its secret too`() = runBlocking {
        val id = repository.save(draft())

        repository.save(
            draft(id = id, password = Keep, accessClientSecret = Keep, headerValue = Keep).copy(accessClientId = " "),
        )

        val profile = checkNotNull(repository.get(id))
        assertEquals("", profile.accessClientId)
        assertFalse(profile.hasAccessClientSecret)
        assertNull(ready(repository.settingsFor(id)).accessClientSecret)
    }

    @Test
    fun `when the key is lost the profile survives but its credentials are unavailable`() = runBlocking {
        val id = repository.save(draft())
        val afterRestore = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)

        assertEquals("Home", checkNotNull(afterRestore.get(id)).name)
        assertEquals(SettingsResult.CredentialsUnavailable, afterRestore.settingsFor(id))
    }

    @Test
    fun `re-entering the secrets after a lost key makes the profile usable again`() = runBlocking {
        val id = repository.save(draft())
        val afterRestore = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)

        afterRestore.save(draft(id = id))

        assertEquals(PASSWORD, ready(afterRestore.settingsFor(id)).password)
    }

    @Test
    fun `an unsaved draft can be turned into settings, taking kept secrets from storage`() = runBlocking {
        val id = repository.save(draft())
        val edited = draft(id = id, password = Keep, accessClientSecret = Keep, headerValue = Keep)
            .copy(baseUrl = "https://other.example.org")

        val settings = ready(repository.settingsFor(edited))

        assertEquals("https://other.example.org/".toHttpUrl(), settings.baseUrl)
        assertEquals(PASSWORD, settings.password)
        assertEquals(
            "the stored profile is untouched",
            "https://nagios.example.org/nagios",
            repository.get(id)?.baseUrl,
        )
    }

    @Test
    fun `a remembered CGI directory is used, and forgotten when the URL changes`() = runBlocking {
        val id = repository.save(draft())
        repository.rememberCgiBase(id, "https://nagios.example.org/nagios/cgi-bin/".toHttpUrl())
        assertEquals(
            "https://nagios.example.org/nagios/cgi-bin/".toHttpUrl(),
            ready(repository.settingsFor(id)).cgiBase,
        )

        repository.save(draft(id = id, password = Keep, accessClientSecret = Keep, headerValue = Keep))
        assertEquals(
            "unchanged URL keeps it",
            "/nagios/cgi-bin/",
            ready(repository.settingsFor(id)).cgiBase?.encodedPath,
        )

        repository.save(
            draft(
                id = id,
                password = Keep,
                accessClientSecret = Keep,
                headerValue = Keep,
            ).copy(baseUrl = "https://b.example.org"),
        )
        assertNull(ready(repository.settingsFor(id)).cgiBase)
    }

    @Test
    fun `profiles are listed by name and deletion removes them`() = runBlocking {
        val zulu = repository.save(draft().copy(name = "zulu"))
        repository.save(draft().copy(name = "Alpha"))

        assertEquals(listOf("Alpha", "zulu"), repository.observeProfiles().first().map { it.name })

        repository.delete(zulu)
        assertEquals(listOf("Alpha"), repository.observeProfiles().first().map { it.name })
        assertEquals(SettingsResult.NotFound, repository.settingsFor(zulu))
    }

    @Test
    fun `a stored URL that no longer parses is reported, not thrown`() = runBlocking {
        val id = repository.save(draft().copy(baseUrl = "not a url"))

        assertEquals(SettingsResult.InvalidUrl, repository.settingsFor(id))
    }

    private fun ready(result: SettingsResult): ConnectionSettings =
        (result as? SettingsResult.Ready)?.settings ?: throw AssertionError("expected Ready but got $result")

    private fun draft(
        id: Long? = null,
        password: SecretInput = Replace(PASSWORD),
        accessClientSecret: SecretInput = Replace(ACCESS_SECRET),
        headerValue: SecretInput = Replace(HEADER_VALUE),
    ) = ProfileDraft(
        id = id,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        password = password,
        accessClientId = "id.access",
        accessClientSecret = accessClientSecret,
        customHeaders = listOf(HeaderDraft("X-Proxy-Token", headerValue)),
        allowCleartext = false,
    )

    private companion object {
        const val PASSWORD = "p4ssw0rd-do-not-store"
        const val ACCESS_SECRET = "access-secret-do-not-store"
        const val HEADER_VALUE = "header-value-do-not-store"
    }
}

internal fun context(): android.content.Context = ApplicationProvider.getApplicationContext()

internal fun inMemoryProfileDatabase(): ProfileDatabase =
    Room.inMemoryDatabaseBuilder(context(), ProfileDatabase::class.java).allowMainThreadQueries().build()
