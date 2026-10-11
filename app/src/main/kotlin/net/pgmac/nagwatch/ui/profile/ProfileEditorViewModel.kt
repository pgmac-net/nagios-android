// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.localnet.LocalNetwork
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.notify.NotificationAccess
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.ProfileValidator
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.ui.PermissionRequest
import net.pgmac.nagwatch.ui.localnet.LocalNetworkRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProfileRepository,
    private val tester: ConnectionTester,
    private val network: LocalNetwork,
    private val notificationAccess: NotificationAccess,
) : ViewModel() {
    /** "Test connection" is held back until the question about local network access is answered. */
    private var awaitingTest = false

    private val editedId: Long? = savedStateHandle.get<Long>(ARG_PROFILE_ID)?.takeIf { it > 0 }
    private val mutableState = MutableStateFlow(ProfileEditorState(profileId = editedId))
    val state: StateFlow<ProfileEditorState> = mutableState.asStateFlow()

    private var stored: Profile? = null

    /** The CGI directory found by the last successful test, and the URL it was found under. */
    private var tested: Pair<String, HttpUrl>? = null

    init {
        viewModelScope.launch {
            stored = editedId?.let { repository.get(it) }
            mutableState.value = stored?.let(ProfileEditorState::from)
                // Switched on earlier, and Android has since stopped the app showing anything: say so.
                ?.let { it.copy(notificationsRefused = it.notifications.enabled && !notificationAccess.isHeld()) }
                ?: ProfileEditorState(loading = false)
        }
    }

    /** Any edit clears the last test result: it no longer describes what is on screen. */
    fun edit(change: (ProfileEditorState) -> ProfileEditorState) = mutableState.update { current ->
        change(current).copy(test = TestState.Idle).let { edited ->
            // Once problems are showing, keep them current as the user fixes them.
            if (current.problems.isEmpty()) edited else edited.copy(problems = problemsOf(edited))
        }
    }

    fun testConnection() {
        if (!validate()) return
        mutableState.update { it.copy(test = TestState.Running) }
        viewModelScope.launch {
            val host = state.value.baseUrl.trim().toHttpUrlOrNull()?.host
            if (host != null && network.shouldAsk(host)) {
                awaitingTest = true
                mutableState.update { it.copy(test = TestState.Idle, explainLocalNetwork = true) }
                return@launch
            }
            runTest()
        }
    }

    /** "Continue": the explanation is done with; the screen now raises Android's prompt. */
    fun localNetworkExplained() {
        mutableState.update { it.copy(explainLocalNetwork = false) }
        viewModelScope.launch { network.explained() }
    }

    /** "Not now". The test is still run: a VPN needs no permission. */
    fun localNetworkDeclined() {
        mutableState.update { it.copy(explainLocalNetwork = false) }
        viewModelScope.launch {
            network.explained()
            proceed()
        }
    }

    /** Android's prompt came back, or the button's did. A test that was asked for goes ahead whatever the answer. */
    fun localNetworkAnswered(granted: Boolean, canAskAgain: Boolean) {
        viewModelScope.launch {
            network.answered(granted, canAskAgain)
            if (awaitingTest || granted) proceed()
        }
    }

    /** The button on the error: Android's prompt, or its settings page once it will not show the prompt. */
    fun allowLocalNetwork(request: LocalNetworkRequest) {
        viewModelScope.launch { if (network.exhausted()) request.openSettings() else request.askAndroid() }
    }

    private suspend fun proceed() {
        awaitingTest = false
        runTest()
    }

    /**
     * The notifications switch, and the button shown after a refusal, which is the switch again.
     * Off is a plain edit. On is one too if Android already allows notifications; otherwise
     * Android is asked first, and the switch follows its answer.
     */
    fun setNotifications(enabled: Boolean, request: PermissionRequest) {
        if (enabled && !notificationAccess.isHeld()) {
            allowNotifications(request)
        } else {
            mutableState.update {
                it.copy(notifications = it.notifications.copy(enabled = enabled), notificationsRefused = false)
            }
        }
    }

    /** Android's prompt, or its settings page once it will not show the prompt. */
    private fun allowNotifications(request: PermissionRequest) {
        viewModelScope.launch {
            if (notificationAccess.exhausted()) {
                // Nothing comes back from the settings page. The switch is tried again on return.
                mutableState.update { it.copy(notificationsRefused = true) }
                request.openSettings()
            } else {
                request.askAndroid()
            }
        }
    }

    /** Android's prompt came back. Refused, the switch stays off and the section says why. */
    fun notificationsAnswered(granted: Boolean, canAskAgain: Boolean) {
        viewModelScope.launch {
            notificationAccess.answered(granted, canAskAgain)
            mutableState.update {
                it.copy(notifications = it.notifications.copy(enabled = granted), notificationsRefused = !granted)
            }
        }
    }

    private suspend fun runTest() {
        val draft = state.value.toDraft()
        mutableState.update { it.copy(test = TestState.Running) }
        val outcome = when (val settings = repository.settingsFor(draft)) {
            is SettingsResult.Ready -> when (val result = tester.test(settings.settings)) {
                is NagiosResult.Success -> {
                    tested = draft.baseUrl.trim() to result.value.cgiBase
                    val readOnly = tester.mayCommand(settings.settings) == false
                    TestState.Succeeded(result.value.server.version, readOnly)
                }

                is NagiosResult.Failure -> TestState.Failed(result.error)
            }

            else -> TestState.CredentialsUnavailable
        }
        mutableState.update { it.copy(test = outcome) }
    }

    fun save() {
        if (!validate()) return
        val draft = state.value.toDraft()
        viewModelScope.launch {
            val id = repository.save(draft)
            tested?.takeIf { (url, _) -> url == draft.baseUrl.trim() }?.let { (_, cgiBase) ->
                repository.rememberCgiBase(id, cgiBase)
            }
            mutableState.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val id = editedId ?: return
        viewModelScope.launch {
            repository.delete(id)
            mutableState.update { it.copy(done = true) }
        }
    }

    private fun validate(): Boolean {
        val problems = problemsOf(state.value)
        mutableState.update { it.copy(problems = problems) }
        return problems.isEmpty()
    }

    private fun problemsOf(state: ProfileEditorState) = ProfileValidator.validate(state.toDraft(), stored)

    companion object {
        const val ARG_PROFILE_ID = "profileId"
    }
}
