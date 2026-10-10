// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.DetailRepository
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.cache.DetailCache
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.comment
import net.pgmac.nagwatch.status.failure
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.saveTestProfile
import net.pgmac.nagwatch.status.snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DetailViewModelTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val statusDatabase = inMemoryStatusDatabase()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val statuses = StatusRepository(profiles, provider(client), StatusCache(statusDatabase.cache()), clock)
    private val details =
        DetailRepository(profiles, provider(client), DetailCache(statusDatabase.cache(), clock), clock)
    private val created = mutableListOf<DetailViewModel>()

    private val web01 = HostStatus("web01", HostState.UP, CheckStatus(pluginOutput = "PING OK"))
    private val ping = ServiceStatus("web01", "Ping", ServiceState.OK, CheckStatus(pluginOutput = "rta 0.4ms"))
    private var serviceFetches = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        client.hostResult = { NagiosResult.Success(web01) }
        client.serviceResult = {
            serviceFetches++
            NagiosResult.Success(ping)
        }
        client.commentsResult = { NagiosResult.Success(listOf(comment(1))) }
    }

    @After
    fun tearDown() {
        // A view model left running can reach for Dispatchers.Main while the next test replaces it.
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        profileDatabase.close()
        statusDatabase.close()
    }

    @Test
    fun `it loads what its arguments name as soon as it exists, without being asked`() {
        val id = saveProfile()

        val state = service(id, "web01", "Ping").awaitState { !it.detail.refreshing && it.detail.record != null }

        assertEquals("web01 / Ping", state.title)
        assertEquals(ping, (state.detail.record as CachedDetail.Service).status)
        assertEquals(listOf(comment(1)), state.detail.comments.items)
        assertEquals(1, serviceFetches)
    }

    @Test
    fun `with no service argument it is a host`() {
        val id = saveProfile()

        val viewModel = host(id, "web01")

        assertEquals(ObjectRef("web01"), viewModel.ref)
        val state = viewModel.awaitState { !it.detail.refreshing && it.detail.record != null }
        assertEquals(web01, (state.detail.record as CachedDetail.Host).status)
    }

    @Test
    fun `awkward names arrive untouched`() {
        val id = saveProfile()

        val viewModel = service(id, "a b", "Disk /var (50%) & more")

        assertEquals(ObjectRef("a b", "Disk /var (50%) & more"), viewModel.ref)
    }

    @Test
    fun `a host shows its services from the last poll beside its own record`() {
        val id = saveProfile()
        client.statusResult = {
            NagiosResult.Success(
                snapshot(hosts = listOf(web01)).copy(
                    serviceStates = listOf(ServiceStateEntry("web01", "Ping", ServiceState.OK)),
                ),
            )
        }
        runBlocking { statuses.refresh(id) }

        val state = host(id, "web01").awaitState { it.services.isNotEmpty() && !it.detail.refreshing }

        assertEquals(listOf("Ping"), state.services.map { it.service })
    }

    @Test
    fun `refresh asks Nagios again and keeps what is on screen meanwhile`() {
        val id = saveProfile()
        val viewModel = service(id, "web01", "Ping")
        viewModel.awaitState { !it.detail.refreshing && it.detail.record != null }

        viewModel.refresh()

        val state = viewModel.awaitState { serviceFetches == 2 && !it.detail.refreshing }
        assertEquals(ping, (state.detail.record as CachedDetail.Service).status)
    }

    @Test
    fun `when nothing can be loaded the reason reaches the screen`() {
        val id = saveProfile()
        client.serviceResult = { failure() }

        val state = service(id, "web01", "Ping").awaitState { it.detail.error != null && !it.detail.refreshing }

        assertNull(state.detail.record)
        assertTrue(state.detail.comments.items.isNotEmpty())
    }

    private fun host(profileId: Long, name: String) = DetailViewModel(
        SavedStateHandle(mapOf(DetailViewModel.ARG_PROFILE to profileId, DetailViewModel.ARG_HOST to name)),
        details,
        statuses,
    ).also(created::add)

    private fun service(profileId: Long, hostName: String, description: String) = DetailViewModel(
        SavedStateHandle(
            mapOf(
                DetailViewModel.ARG_PROFILE to profileId,
                DetailViewModel.ARG_HOST to hostName,
                DetailViewModel.ARG_SERVICE to description,
            ),
        ),
        details,
        statuses,
    ).also(created::add)

    private fun DetailViewModel.awaitState(predicate: (DetailUiState) -> Boolean): DetailUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }

    private fun saveProfile(): Long = runBlocking { profiles.saveTestProfile() }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
