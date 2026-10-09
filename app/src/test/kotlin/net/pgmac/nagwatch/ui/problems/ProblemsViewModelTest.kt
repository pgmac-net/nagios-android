// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput.Keep
import net.pgmac.nagwatch.profile.SecretInput.Replace
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.FakeSelectedProfile
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.criticalService
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
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
class ProblemsViewModelTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val clock = MutableClock(T0)
    private val statusDatabase = inMemoryStatusDatabase()
    private val cache = StatusCache(statusDatabase.cache())
    private val client = FakeClient()
    private val statuses = StatusRepository(profiles, provider(client), cache, clock)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
        statusDatabase.close()
    }

    @Test
    fun `with no profiles there is nothing selected`() {
        val state = viewModel().awaitState { !it.loading }

        assertNull(state.selected)
        assertTrue(state.profiles.isEmpty())
    }

    @Test
    fun `the first profile is shown until another is chosen`() {
        val home = saveProfile("Home")
        val office = saveProfile("Office")
        val viewModel = viewModel()

        assertEquals(home, viewModel.awaitState { it.selected != null }.selected?.id)

        viewModel.select(office)
        assertEquals(office, viewModel.awaitState { it.selected?.id == office }.selected?.id)
    }

    @Test
    fun `a chosen profile is remembered by a view model created later`() {
        saveProfile("Home")
        val office = saveProfile("Office")
        val remembered = FakeSelectedProfile()

        val first = viewModel(remembered)
        first.select(office)
        first.awaitState { it.selected?.id == office }

        assertEquals(office, viewModel(remembered).awaitState { it.selected != null }.selected?.id)
    }

    @Test
    fun `a remembered profile that was since deleted falls back to the first`() {
        val home = saveProfile("Home")

        assertEquals(
            home,
            viewModel(FakeSelectedProfile(initial = 999)).awaitState {
                it.selected != null
            }.selected?.id,
        )
    }

    @Test
    fun `switching profile clears the filter`() {
        saveProfile("Home")
        val office = saveProfile("Office")
        val viewModel = viewModel()
        viewModel.awaitState { it.selected != null }
        viewModel.toggleFilter(ProblemKind.CRITICAL)
        assertEquals(setOf(ProblemKind.CRITICAL), viewModel.awaitState { it.filter.isNotEmpty() }.filter)

        viewModel.select(office)

        assertTrue(viewModel.awaitState { it.selected?.id == office }.filter.isEmpty())
    }

    @Test
    fun `toggling a filter twice turns it off`() {
        saveProfile("Home")
        val viewModel = viewModel()
        viewModel.awaitState { it.selected != null }

        viewModel.toggleFilter(ProblemKind.WARNING)
        viewModel.toggleFilter(ProblemKind.WARNING)

        assertTrue(viewModel.awaitState { it.selected != null }.filter.isEmpty())
    }

    @Test
    fun `the selected profile's status reaches the screen state`() {
        val id = saveProfile("Home")
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        val viewModel = viewModel()
        viewModel.awaitState { it.selected != null }

        viewModel.refresh()

        val state = viewModel.awaitState { it.status?.report != null }
        assertEquals(1, state.status?.report?.counts?.critical)
        assertEquals(id, state.selected?.id)
    }

    @Test
    fun `opening the screen fetches once, and coming back soon after does not fetch again`() {
        saveProfile("Home")
        val viewModel = viewModel()
        viewModel.awaitState { it.selected != null }

        viewModel.refreshIfNeeded()
        viewModel.awaitState { it.status?.report != null }
        viewModel.refreshIfNeeded()

        assertEquals(1, client.fetches)
    }

    @Test
    fun `pull to refresh always fetches`() {
        saveProfile("Home")
        val viewModel = viewModel()
        viewModel.awaitState { it.selected != null }
        viewModel.refreshIfNeeded()
        viewModel.awaitState { it.status?.report != null && !it.status.refreshing }

        viewModel.refresh()

        awaitFetches(2)
        assertEquals(2, client.fetches)
    }

    /** The fetch runs off the test thread (it goes through the database first), so wait for it. */
    private fun awaitFetches(count: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (client.fetches < count) delay(POLL_MS)
        }
    }

    private fun viewModel(selected: FakeSelectedProfile = FakeSelectedProfile()) =
        ProblemsViewModel(profiles, statuses, selected)

    private fun ProblemsViewModel.awaitState(predicate: (ProblemsUiState) -> Boolean): ProblemsUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }

    private fun saveProfile(name: String): Long = runBlocking {
        profiles.save(
            ProfileDraft(
                id = null,
                name = name,
                baseUrl = "https://nagios.example.org/nagios",
                username = "nagwatch",
                password = Replace("pw"),
                accessClientId = "",
                accessClientSecret = Keep,
                customHeaders = emptyList(),
                allowCleartext = false,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
