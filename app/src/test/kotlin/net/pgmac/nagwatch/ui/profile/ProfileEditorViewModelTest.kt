// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.localnet.FakeLocalNetworkHistory
import net.pgmac.nagwatch.localnet.FakeLocalNetworkPermission
import net.pgmac.nagwatch.localnet.TestLocalNetwork
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.notify.TestNotificationAccess
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.ui.PermissionRequest
import net.pgmac.nagwatch.ui.localnet.LocalNetworkRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
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
class ProfileEditorViewModelTest {
    private val database = inMemoryProfileDatabase()
    private val repository = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val tester = RecordingTester()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `a new profile starts empty and saves what was typed`() {
        val viewModel = editor()
        viewModel.fill()

        viewModel.save()
        viewModel.awaitState { it.done }

        val saved = runBlocking { repository.observeProfiles().first() }.single()
        assertEquals("Home", saved.name)
        assertEquals(PASSWORD, runBlocking { settings(saved.id) }.password)
    }

    @Test
    fun `saving an invalid profile shows the problems and stores nothing`() {
        val viewModel = editor()

        viewModel.save()

        assertTrue(ProfileProblem.NAME_REQUIRED in viewModel.state.value.problems)
        assertFalse(viewModel.state.value.done)
        assertTrue(runBlocking { repository.observeProfiles().first() }.isEmpty())
    }

    @Test
    fun `problems stay current as they are fixed`() {
        val viewModel = editor()
        viewModel.save()

        viewModel.edit { it.copy(name = "Home") }

        assertFalse(ProfileProblem.NAME_REQUIRED in viewModel.state.value.problems)
        assertTrue(ProfileProblem.URL_REQUIRED in viewModel.state.value.problems)
    }

    @Test
    fun `editing never loads a stored secret into the form`() {
        val id = saveProfile()

        val viewModel = editor(id)

        val state = viewModel.state.value
        assertEquals("Home", state.name)
        assertTrue(state.password.stored)
        assertFalse(state.password.editing)
        assertEquals("", state.password.input)
        assertFalse("state must not carry the secret anywhere", state.toString().contains(PASSWORD))
    }

    @Test
    fun `saving an edit without touching the password keeps it`() {
        val id = saveProfile()
        val viewModel = editor(id)

        viewModel.edit { it.copy(name = "Renamed") }
        viewModel.save()
        viewModel.awaitState { it.done }

        assertEquals("Renamed", runBlocking { repository.get(id) }?.name)
        assertEquals(PASSWORD, runBlocking { settings(id) }.password)
    }

    @Test
    fun `a test says so when Nagios takes no commands from the user`() {
        val id = saveProfile()
        val viewModel = editor(id)
        tester.mayCommand = false

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        assertEquals(TestState.Succeeded("4.5.9", readOnly = true), viewModel.state.value.test)
    }

    @Test
    fun `not being able to tell is not called read-only, and a failed test does not ask`() {
        val id = saveProfile()
        val viewModel = editor(id)
        tester.mayCommand = null

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }
        assertEquals(TestState.Succeeded("4.5.9", readOnly = false), viewModel.state.value.test)
        assertEquals(1, tester.asked)

        tester.result = NagiosResult.Failure(NagiosError.BadCredentials)
        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Failed }
        assertEquals("nothing more is asked of a Nagios that would not let us in", 1, tester.asked)
    }

    @Test
    fun `testing a local-looking address asks first, and does not test yet`() {
        val local = asking()
        val viewModel = editor(local = local)
        viewModel.fill()

        viewModel.testConnection()
        val state = viewModel.awaitStateValue { it.explainLocalNetwork }

        assertEquals(TestState.Idle, state.test)
        assertEquals(0, tester.tests)
    }

    @Test
    fun `Not now is remembered, and the test is run anyway`() {
        val local = asking()
        val viewModel = editor(local = local)
        viewModel.fill()
        viewModel.testConnection()
        viewModel.awaitState { it.explainLocalNetwork }

        viewModel.localNetworkDeclined()
        viewModel.awaitState { it.test is TestState.Succeeded }

        assertEquals(1, tester.tests)
        assertTrue(local.history.explained)
        assertFalse(viewModel.state.value.explainLocalNetwork)
    }

    @Test
    fun `Continue waits for Android, and the test then runs whatever Android said`() {
        listOf(true to true, false to true, false to false).forEach { (granted, canAskAgain) ->
            tester.tests = 0
            val local = asking()
            val viewModel = editor(local = local)
            viewModel.fill()
            viewModel.testConnection()
            viewModel.awaitState { it.explainLocalNetwork }

            viewModel.localNetworkExplained()
            viewModel.awaitState { !it.explainLocalNetwork }
            pause()
            assertEquals("not before Android has answered", 0, tester.tests)

            viewModel.localNetworkAnswered(granted, canAskAgain)
            viewModel.awaitState { it.test is TestState.Succeeded }

            assertEquals(1, tester.tests)
            assertEquals(!granted && !canAskAgain, local.history.exhausted)
        }
    }

    @Test
    fun `once explained, the next test runs at once`() {
        val viewModel = editor(local = asking(explained = true))
        viewModel.fill()

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        assertEquals(1, tester.tests)
        assertFalse(viewModel.state.value.explainLocalNetwork)
    }

    @Test
    fun `an address that is not local-looking is never asked about`() {
        val viewModel = editor(local = asking(addresses = listOf("93.184.216.34")))
        viewModel.fill()

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        assertFalse(viewModel.state.value.explainLocalNetwork)
    }

    @Test
    fun `after a test that failed for this reason, a grant tests again and a refusal does not`() {
        tester.result = NagiosResult.Failure(NagiosError.LocalNetworkBlocked)
        val viewModel = editor(local = asking(explained = true))
        viewModel.fill()
        viewModel.testConnection()
        viewModel.awaitState { it.test == TestState.Failed(NagiosError.LocalNetworkBlocked) }
        assertEquals(1, tester.tests)

        viewModel.localNetworkAnswered(granted = false, canAskAgain = true)
        pause()
        assertEquals("a refusal is not a reason to test again", 1, tester.tests)

        tester.result = NagiosResult.Success(tester.connection)
        viewModel.localNetworkAnswered(granted = true, canAskAgain = true)
        viewModel.awaitState { it.test is TestState.Succeeded }
        assertEquals(2, tester.tests)
    }

    @Test
    fun `the button raises Android's prompt, and opens settings once Android has stopped showing it`() {
        val local = asking(explained = true)
        val viewModel = editor(local = local)
        val calls = mutableListOf<String>()
        val request = LocalNetworkRequest({ calls += "prompt" }, { calls += "settings" })

        viewModel.allowLocalNetwork(request)
        awaitCalls(calls, 1)
        local.history.exhausted = true
        viewModel.allowLocalNetwork(request)
        awaitCalls(calls, 2)

        assertEquals(listOf("prompt", "settings"), calls)
    }

    private fun awaitCalls(calls: List<String>, count: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (calls.size < count) delay(POLL_MS)
        }
    }

    private fun ProfileEditorViewModel.awaitStateValue(predicate: (ProfileEditorState) -> Boolean): ProfileEditorState {
        awaitState(predicate)
        return state.value
    }

    @Test
    fun `notifications switch on at once when Android already allows them, and are saved with the profile`() {
        val viewModel = editor(notifications = TestNotificationAccess(held = true))
        val calls = mutableListOf<String>()
        viewModel.fill()

        viewModel.setNotifications(true, recording(calls))
        viewModel.edit {
            it.copy(notifications = it.notifications.copy(interval = PollInterval.ONE_HOUR, tellUnreachable = false))
        }
        viewModel.save()
        viewModel.awaitState { it.done }

        assertTrue("Android was not asked for what it had already given", calls.isEmpty())
        val saved = runBlocking { repository.observeProfiles().first() }.single()
        assertEquals(
            NotificationSettings(enabled = true, interval = PollInterval.ONE_HOUR, tellUnreachable = false),
            saved.notifications,
        )
        assertEquals("the editor shows what was saved", saved.notifications, editor(saved.id).state.value.notifications)
    }

    @Test
    fun `switching notifications on asks Android first, and the switch follows its answer`() {
        val viewModel = editor(notifications = TestNotificationAccess(held = false))
        val calls = mutableListOf<String>()

        viewModel.setNotifications(true, recording(calls))
        awaitCalls(calls, 1)

        assertEquals(listOf("prompt"), calls)
        assertFalse("not before Android has answered", viewModel.state.value.notifications.enabled)

        viewModel.notificationsAnswered(granted = true, canAskAgain = true)
        val on = viewModel.awaitStateValue { it.notifications.enabled }
        assertFalse(on.notificationsRefused)
    }

    @Test
    fun `when Android says no the switch stays off and the editor says why`() {
        val access = TestNotificationAccess(held = false)
        val viewModel = editor(notifications = access)
        val calls = mutableListOf<String>()
        viewModel.setNotifications(true, recording(calls))
        awaitCalls(calls, 1)

        viewModel.notificationsAnswered(granted = false, canAskAgain = true)

        val refused = viewModel.awaitStateValue { it.notificationsRefused }
        assertFalse(refused.notifications.enabled)
        assertFalse("Android would still ask", access.history.exhausted)

        // Trying again asks again while Android still will, and opens its settings once it will not.
        viewModel.setNotifications(true, recording(calls))
        awaitCalls(calls, 2)
        viewModel.notificationsAnswered(granted = false, canAskAgain = false)
        viewModel.setNotifications(true, recording(calls))
        awaitCalls(calls, 3)

        assertEquals(listOf("prompt", "prompt", "settings"), calls)
        assertFalse(viewModel.state.value.notifications.enabled)
    }

    @Test
    fun `after allowing them in settings, the switch works and the refusal is gone`() {
        val access = TestNotificationAccess(held = false).also { it.history.exhausted = true }
        val viewModel = editor(notifications = access)
        val calls = mutableListOf<String>()
        viewModel.setNotifications(true, recording(calls))
        awaitCalls(calls, 1)
        assertEquals(listOf("settings"), calls)
        viewModel.awaitState { it.notificationsRefused }

        access.held = true
        viewModel.setNotifications(true, recording(calls))

        assertTrue(viewModel.state.value.notifications.enabled)
        assertFalse(viewModel.state.value.notificationsRefused)
        assertEquals("nothing more was asked", 1, calls.size)
    }

    @Test
    fun `switching notifications off never asks Android anything`() {
        val id = saveProfile()
        runBlocking { repository.notificationsOffered(id, accepted = true) }
        val viewModel = editor(id, notifications = TestNotificationAccess(held = false))
        val calls = mutableListOf<String>()
        assertTrue(viewModel.state.value.notifications.enabled)

        viewModel.setNotifications(false, recording(calls))
        pause()

        assertFalse(viewModel.state.value.notifications.enabled)
        assertTrue(calls.isEmpty())
    }

    private fun recording(calls: MutableList<String>) = PermissionRequest({
        calls += "prompt"
    }, { calls += "settings" })

    @Test
    fun `test connection uses what is on screen, with kept secrets from storage`() {
        val id = saveProfile()
        val viewModel = editor(id)
        viewModel.edit { it.copy(baseUrl = "https://other.example.org") }

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        assertEquals(TestState.Succeeded("4.5.9"), viewModel.state.value.test)
        assertEquals("https://other.example.org/".toHttpUrl(), tester.last?.baseUrl)
        assertEquals(PASSWORD, tester.last?.password)
        assertEquals(
            "testing must not save",
            "https://nagios.example.org/nagios",
            runBlocking {
                repository.get(id)
            }?.baseUrl,
        )
    }

    @Test
    fun `a failed test shows the error class it failed with`() {
        tester.result = NagiosResult.Failure(NagiosError.BadCredentials)
        val viewModel = editor()
        viewModel.fill()

        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Failed }

        assertEquals(TestState.Failed(NagiosError.BadCredentials), viewModel.state.value.test)
    }

    @Test
    fun `an invalid profile is not tested`() {
        val viewModel = editor()
        viewModel.fill()
        viewModel.edit { it.copy(baseUrl = "http://nagios.lan") }

        viewModel.testConnection()

        assertEquals(setOf(ProfileProblem.CLEARTEXT_NOT_ALLOWED), viewModel.state.value.problems)
        assertNull("nothing may be sent", tester.last)
    }

    @Test
    fun `editing after a test clears its result`() {
        val viewModel = editor()
        viewModel.fill()
        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        viewModel.edit { it.copy(username = "someone-else") }

        assertEquals(TestState.Idle, viewModel.state.value.test)
    }

    @Test
    fun `the CGI directory found by a test is remembered on save`() {
        val viewModel = editor()
        viewModel.fill()
        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }

        viewModel.save()
        viewModel.awaitState { it.done }

        val id = runBlocking { repository.observeProfiles().first() }.single().id
        assertEquals(CGI_BASE.toHttpUrl(), runBlocking { settings(id) }.cgiBase)
    }

    @Test
    fun `a test result for a different URL is not remembered`() {
        val viewModel = editor()
        viewModel.fill()
        viewModel.testConnection()
        viewModel.awaitState { it.test is TestState.Succeeded }
        viewModel.edit { it.copy(baseUrl = "https://elsewhere.example.org") }

        viewModel.save()
        viewModel.awaitState { it.done }

        val id = runBlocking { repository.observeProfiles().first() }.single().id
        assertNull(runBlocking { settings(id) }.cgiBase)
    }

    @Test
    fun `delete removes the profile and closes the editor`() {
        val id = saveProfile()
        val viewModel = editor(id)

        viewModel.delete()
        viewModel.awaitState { it.done }

        assertNull(runBlocking { repository.get(id) })
    }

    private fun editor(
        id: Long? = null,
        local: TestLocalNetwork = TestLocalNetwork(FakeLocalNetworkPermission(true)),
        notifications: TestNotificationAccess = TestNotificationAccess(held = true),
    ): ProfileEditorViewModel {
        val handle = SavedStateHandle(mapOf(ProfileEditorViewModel.ARG_PROFILE_ID to (id ?: 0L)))
        return ProfileEditorViewModel(handle, repository, tester, local.network, notifications.access)
            .also { vm -> vm.awaitState { !it.loading } }
    }

    private fun asking(explained: Boolean = false, addresses: List<String> = listOf("192.168.1.20")) =
        TestLocalNetwork(FakeLocalNetworkPermission(held = false), FakeLocalNetworkHistory(explained), addresses)

    /** For the cases that are about something not happening: give it a moment to happen. */
    private fun pause() = runBlocking { delay(NOTHING_HAPPENS_MS) }

    private fun saveProfile(): Long {
        val viewModel = editor()
        viewModel.fill()
        viewModel.save()
        viewModel.awaitState { it.done }
        return runBlocking { repository.observeProfiles().first() }.single().id
    }

    private fun ProfileEditorViewModel.fill() = edit {
        it.copy(
            name = "Home",
            baseUrl = "https://nagios.example.org/nagios",
            username = "nagwatch",
            password = it.password.copy(editing = true, input = PASSWORD),
        )
    }

    private fun ProfileEditorViewModel.awaitState(predicate: (ProfileEditorState) -> Boolean) {
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }
    }

    private suspend fun settings(id: Long): ConnectionSettings =
        (repository.settingsFor(id) as SettingsResult.Ready).settings

    private class RecordingTester : ConnectionTester {
        var last: ConnectionSettings? = null
        var tests = 0
        val connection = NagiosClient.Connection(
            CGI_BASE.toHttpUrl(),
            ServerInfo("4.5.9", Instant.EPOCH, Instant.EPOCH),
        )
        var result: NagiosResult<NagiosClient.Connection> = NagiosResult.Success(connection)

        override suspend fun test(settings: ConnectionSettings): NagiosResult<NagiosClient.Connection> {
            last = settings
            tests++
            return result
        }

        var mayCommand: Boolean? = true
        var asked = 0

        override suspend fun mayCommand(settings: ConnectionSettings): Boolean? {
            asked++
            return mayCommand
        }
    }

    private companion object {
        const val PASSWORD = "p4ssw0rd-do-not-leak"
        const val CGI_BASE = "https://nagios.example.org/nagios/cgi-bin/"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val NOTHING_HAPPENS_MS = 300L
    }
}
