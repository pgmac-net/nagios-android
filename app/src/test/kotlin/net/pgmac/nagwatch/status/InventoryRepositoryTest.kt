// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceGroup
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The configuration behind the grouped views: read once, kept, read again only when asked. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class InventoryRepositoryTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val repository = InventoryRepository(profiles, provider(client))
    private var profile = 0L

    private val webServers = HostGroup("web", "Web servers", listOf("host01"))

    @Before
    fun save() = runBlocking { profile = profiles.saveTestProfile() }

    @After
    fun close() = profileDatabase.close()

    @Test
    fun `host groups are read once and then kept`() = runBlocking {
        client.hostGroupsResult = { NagiosResult.Success(listOf(webServers)) }

        repository.loadHostGroups(profile)
        repository.loadHostGroups(profile)

        assertEquals(listOf(webServers), repository.inventories.value.getValue(profile).hostGroups.value)
        assertEquals(1, client.groupFetches)
    }

    @Test
    fun `a failure keeps what was known, says why, and stops loading`() = runBlocking {
        client.hostGroupsResult = { NagiosResult.Success(listOf(webServers)) }
        repository.loadHostGroups(profile)
        client.hostGroupsResult = { NagiosResult.Failure(NagiosError.Forbidden) }

        repository.loadHostGroups(profile, force = true)

        val part = repository.inventories.value.getValue(profile).hostGroups
        assertEquals(listOf(webServers), part.value)
        assertEquals(StatusError.Nagios(NagiosError.Forbidden), part.error)
        assertFalse(part.loading)
    }

    @Test
    fun `a refresh reads again only what was read before`() = runBlocking {
        client.serviceGroupsResult =
            { NagiosResult.Success(listOf(ServiceGroup("s", "", listOf(ObjectRef("a", "b"))))) }
        repository.loadServiceGroups(profile)

        repository.refreshLoaded(profile)

        assertEquals("service groups twice, host groups never", 2, client.groupFetches)
        assertNull(repository.inventories.value.getValue(profile).hostGroups.value)
    }

    @Test
    fun `hosts' parents are read once, and again on a refresh`() = runBlocking {
        client.hostConfigsResult = { NagiosResult.Success(listOf(HostConfig("host02", listOf("host01")))) }

        repository.loadHostConfigs(profile)
        repository.loadHostConfigs(profile)
        repository.refreshLoaded(profile)

        assertEquals(2, client.groupFetches)
        assertEquals(
            listOf("host01"),
            repository.inventories.value.getValue(profile).hostConfigs.value?.single()?.parents,
        )
    }

    @Test
    fun `a profile that is gone is said so`() = runBlocking {
        repository.loadHostGroups(MISSING)

        assertEquals(StatusError.ProfileMissing, repository.inventories.value.getValue(MISSING).hostGroups.error)
    }

    @Test
    fun `profiles are kept apart`() = runBlocking {
        client.hostGroupsResult = { NagiosResult.Success(listOf(webServers)) }
        repository.loadHostGroups(profile)

        assertNull(repository.inventories.value[OTHER])
    }

    private companion object {
        const val OTHER = 9_999L
        const val MISSING = 404L
    }
}
