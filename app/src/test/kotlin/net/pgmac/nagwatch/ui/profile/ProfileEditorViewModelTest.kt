// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
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

    private fun editor(id: Long? = null): ProfileEditorViewModel {
        val handle = SavedStateHandle(mapOf(ProfileEditorViewModel.ARG_PROFILE_ID to (id ?: 0L)))
        return ProfileEditorViewModel(handle, repository, tester).also { vm -> vm.awaitState { !it.loading } }
    }

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
        var result: NagiosResult<NagiosClient.Connection> = NagiosResult.Success(
            NagiosClient.Connection(CGI_BASE.toHttpUrl(), ServerInfo("4.5.9", Instant.EPOCH, Instant.EPOCH)),
        )

        override suspend fun test(settings: ConnectionSettings): NagiosResult<NagiosClient.Connection> {
            last = settings
            return result
        }
    }

    private companion object {
        const val PASSWORD = "p4ssw0rd-do-not-leak"
        const val CGI_BASE = "https://nagios.example.org/nagios/cgi-bin/"
        const val TIMEOUT_MS = 5_000L
    }
}
