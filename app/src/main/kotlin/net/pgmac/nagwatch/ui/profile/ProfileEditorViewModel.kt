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
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.ProfileValidator
import net.pgmac.nagwatch.profile.SettingsResult
import okhttp3.HttpUrl

@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProfileRepository,
    private val tester: ConnectionTester,
) : ViewModel() {
    private val editedId: Long? = savedStateHandle.get<Long>(ARG_PROFILE_ID)?.takeIf { it > 0 }
    private val mutableState = MutableStateFlow(ProfileEditorState(profileId = editedId))
    val state: StateFlow<ProfileEditorState> = mutableState.asStateFlow()

    private var stored: Profile? = null

    /** The CGI directory found by the last successful test, and the URL it was found under. */
    private var tested: Pair<String, HttpUrl>? = null

    init {
        viewModelScope.launch {
            stored = editedId?.let { repository.get(it) }
            mutableState.value = stored?.let(ProfileEditorState::from) ?: ProfileEditorState(loading = false)
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
        val draft = state.value.toDraft()
        mutableState.update { it.copy(test = TestState.Running) }
        viewModelScope.launch {
            val outcome = when (val settings = repository.settingsFor(draft)) {
                is SettingsResult.Ready -> when (val result = tester.test(settings.settings)) {
                    is NagiosResult.Success -> {
                        tested = draft.baseUrl.trim() to result.value.cgiBase
                        TestState.Succeeded(result.value.server.version)
                    }

                    is NagiosResult.Failure -> TestState.Failed(result.error)
                }

                else -> TestState.CredentialsUnavailable
            }
            mutableState.update { it.copy(test = outcome) }
        }
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
