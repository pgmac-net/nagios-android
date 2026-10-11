// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.localnet.FakeLocalNetworkHistory
import net.pgmac.nagwatch.localnet.FakeLocalNetworkPermission
import net.pgmac.nagwatch.localnet.TestLocalNetwork
import net.pgmac.nagwatch.localnet.noLocalNetworkQuestions
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
import net.pgmac.nagwatch.status.failure
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.snapshot
import net.pgmac.nagwatch.ui.localnet.LocalNetworkRequest
import net.pgmac.nagwatch.ui.problems.ProblemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class HomeViewModelTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val clock = MutableClock(T0)
    private val statusDatabase = inMemoryStatusDatabase()
    private val cache = StatusCache(statusDatabase.cache())
    private val client = FakeClient()
    private val statuses = StatusRepository(profiles, provider(client), cache, clock)
    private val created = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        // A view model watches the profiles for as long as it lives. Left running, it can reach
        // for Dispatchers.Main from a database thread while the next test is replacing it.
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
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
    fun `a cold start with a saved profile loads by itself, without the screen asking`() {
        // The bug this guards against: the screen asked for a refresh as it started, before
        // the profiles had been read, so nothing was selected yet and nothing ever loaded.
        saveProfile("Home")
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }

        val state = viewModel().awaitState { it.status?.report != null }

        assertEquals(1, state.status?.report?.counts?.critical)
        assertEquals(1, client.fetches)
    }

    @Test
    fun `a cold start shows the cached result before the network answers`() {
        val id = saveProfile("Home")
        client.statusResult = { NagiosResult.Success(snapshot(services = listOf(criticalService()))) }
        runBlocking { statuses.refresh(id) }
        // A new process: an empty repository over the same cache, and a server that fails.
        val offline = FakeClient(statusResult = { failure() })
        val restarted =
            HomeViewModel(
                profiles,
                StatusRepository(profiles, provider(offline), cache, clock),
                FakeSelectedProfile(),
                noLocalNetworkQuestions(),
            ).also(created::add)
        clock.advance(java.time.Duration.ofMinutes(5))

        val state = restarted.awaitState { it.status?.report != null }

        assertEquals("the cached critical is on screen", 1, state.status?.report?.counts?.critical)
    }

    @Test
    fun `choosing another profile loads it`() {
        saveProfile("Home")
        val office = saveProfile("Office")
        val viewModel = viewModel()
        viewModel.awaitState { it.status?.report != null }

        viewModel.select(office)

        val state = viewModel.awaitState { it.selected?.id == office && it.status?.report != null }
        assertEquals(office, state.selected?.id)
        assertEquals("one fetch for each profile", 2, client.fetches)
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

    @Test
    fun `the first load of a local-looking profile asks first, and fetches nothing yet`() {
        saveProfile("Home")
        val local = asking()

        val state = viewModel(local = local).awaitState { it.explainLocalNetwork }

        assertEquals("nothing is fetched while the question is open", 0, client.fetches)
        assertEquals("a profile was selected for it", "Home", state.selected?.name)
    }

    @Test
    fun `Not now is remembered, and the profile is fetched anyway`() {
        saveProfile("Home")
        val local = asking()
        val viewModel = viewModel(local = local)
        viewModel.awaitState { it.explainLocalNetwork }

        viewModel.localNetworkDeclined()

        awaitFetches(1)
        assertTrue("it will not ask again", local.history.explained)
        assertFalse(viewModel.awaitState { !it.explainLocalNetwork }.explainLocalNetwork)
    }

    @Test
    fun `Continue waits for Android, and a grant then fetches`() =
        continueThenAnswer(granted = true, canAskAgain = true)

    @Test
    fun `a first refusal still fetches, and Android may ask again`() =
        continueThenAnswer(granted = false, canAskAgain = true)

    @Test
    fun `a refusal with no prompt left still fetches, and is remembered as the end of the prompt`() =
        continueThenAnswer(granted = false, canAskAgain = false)

    private fun continueThenAnswer(granted: Boolean, canAskAgain: Boolean) {
        saveProfile("Home")
        val local = asking()
        val viewModel = viewModel(local = local)
        viewModel.awaitState { it.explainLocalNetwork }

        viewModel.localNetworkExplained()
        viewModel.awaitState { !it.explainLocalNetwork }
        // The question has been explained and Android's prompt is up. A screen that resumes
        // underneath it (the prompt is another window) must not fetch in the meantime.
        runBlocking { while (!local.history.explained) delay(POLL_MS) }
        viewModel.refreshIfNeeded()
        viewModel.refresh()
        pause()
        assertEquals("not before Android has answered", 0, client.fetches)

        viewModel.localNetworkAnswered(granted, canAskAgain)

        awaitFetches(1)
        assertEquals(!granted && !canAskAgain, local.history.exhausted)
        assertTrue(local.history.explained)
    }

    @Test
    fun `nothing is fetched while the question is open, however a fetch is asked for`() {
        saveProfile("Home")
        val viewModel = viewModel(local = asking())
        viewModel.awaitState { it.explainLocalNetwork }

        viewModel.refresh()
        viewModel.refreshIfNeeded()
        pause()

        assertEquals(0, client.fetches)
    }

    @Test
    fun `once explained, a later start or another profile fetches straight away`() {
        saveProfile("Home")
        val local = asking(explained = true)

        viewModel(local = local).awaitState { it.status?.report != null || it.status?.error != null }

        assertEquals(1, client.fetches)
        assertFalse(runBlocking { local.network.shouldAsk("another.example.org") })
    }

    @Test
    fun `a public address is never asked about`() {
        saveProfile("Home")

        viewModel(local = asking(addresses = listOf("93.184.216.34"))).awaitState { it.status?.report != null }

        assertEquals(1, client.fetches)
    }

    @Test
    fun `a held permission is never asked about`() {
        saveProfile("Home")

        viewModel(local = asking(held = true)).awaitState { it.status?.report != null }

        assertEquals(1, client.fetches)
    }

    @Test
    fun `the button retries after a grant, and does nothing after a refusal`() {
        saveProfile("Home")
        val viewModel = viewModel(local = asking(explained = true))
        viewModel.awaitState { it.status?.report != null || it.status?.error != null }
        awaitFetches(1)

        viewModel.localNetworkAnswered(granted = false, canAskAgain = true)
        pause()
        assertEquals("a refusal is not a reason to try again", 1, client.fetches)

        viewModel.localNetworkAnswered(granted = true, canAskAgain = true)
        awaitFetches(2)
    }

    @Test
    fun `the button raises Android's prompt, and opens settings once Android has stopped showing it`() {
        saveProfile("Home")
        val local = asking(explained = true)
        val viewModel = viewModel(local = local)
        viewModel.awaitState { !it.loading }
        val calls = mutableListOf<String>()
        val request = LocalNetworkRequest({ calls += "prompt" }, { calls += "settings" })

        viewModel.allowLocalNetwork(request)
        awaitCalls(calls, 1)
        local.history.exhausted = true
        viewModel.allowLocalNetwork(request)
        awaitCalls(calls, 2)

        assertEquals(listOf("prompt", "settings"), calls)
    }

    private fun asking(
        held: Boolean = false,
        explained: Boolean = false,
        addresses: List<String> = listOf("192.168.1.20"),
    ) = TestLocalNetwork(FakeLocalNetworkPermission(held), FakeLocalNetworkHistory(explained), addresses)

    /** For the cases that are about something not happening: give it a moment to happen. */
    private fun pause() = runBlocking { delay(NOTHING_HAPPENS_MS) }

    private fun awaitCalls(calls: List<String>, count: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (calls.size < count) delay(POLL_MS)
        }
    }

    /** The fetch runs off the test thread (it goes through the database first), so wait for it. */
    private fun awaitFetches(count: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (client.fetches < count) delay(POLL_MS)
        }
    }

    private fun viewModel(
        selected: FakeSelectedProfile = FakeSelectedProfile(),
        local: TestLocalNetwork = TestLocalNetwork(FakeLocalNetworkPermission(held = true)),
    ) = HomeViewModel(profiles, statuses, selected, local.network).also(created::add)

    private fun HomeViewModel.awaitState(predicate: (HomeUiState) -> Boolean): HomeUiState =
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
        const val NOTHING_HAPPENS_MS = 300L
    }
}
