// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.profile.HeaderDraft
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.profile.SecretInput

/**
 * A secret in the editor. A stored secret is never loaded into the form: the
 * field shows "saved" until the user chooses to replace it, and only what they
 * then type is held here.
 */
data class SecretField(val stored: Boolean, val editing: Boolean = !stored, val input: String = "") {
    fun toInput(): SecretInput = when {
        !editing -> SecretInput.Keep
        input.isEmpty() -> SecretInput.Clear
        else -> SecretInput.Replace(input)
    }

    /** Keeps typed secrets out of logs and crash reports that print state. */
    override fun toString(): String = "SecretField(stored=$stored, editing=$editing, input=<redacted>)"
}

data class HeaderField(val name: String = "", val value: SecretField = SecretField(stored = false))

sealed interface TestState {
    data object Idle : TestState

    data object Running : TestState

    data class Succeeded(val nagiosVersion: String) : TestState

    data class Failed(val error: NagiosError) : TestState

    /** A stored secret could not be decrypted; it has to be entered again. */
    data object CredentialsUnavailable : TestState
}

data class ProfileEditorState(
    val loading: Boolean = true,
    val profileId: Long? = null,
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: SecretField = SecretField(stored = false),
    val accessClientId: String = "",
    val accessClientSecret: SecretField = SecretField(stored = false),
    val headers: List<HeaderField> = emptyList(),
    val allowCleartext: Boolean = false,
    /** Shown once the user has tried to save or test; not while they are still typing a first draft. */
    val problems: Set<ProfileProblem> = emptySet(),
    val test: TestState = TestState.Idle,
    /** Set when the editor has finished (saved or deleted) and should close. */
    val done: Boolean = false,
) {
    val isNew: Boolean get() = profileId == null

    /** Drives the unencrypted-HTTP warning and opt-in, as soon as the URL says `http://`. */
    val isCleartextUrl: Boolean get() = baseUrl.trim().startsWith("http://", ignoreCase = true)

    fun toDraft() = ProfileDraft(
        id = profileId,
        name = name,
        baseUrl = baseUrl,
        username = username,
        password = password.toInput(),
        accessClientId = accessClientId,
        accessClientSecret = accessClientSecret.toInput(),
        customHeaders = headers.map { HeaderDraft(it.name, it.value.toInput()) },
        allowCleartext = allowCleartext,
    )

    companion object {
        fun from(profile: Profile) = ProfileEditorState(
            loading = false,
            profileId = profile.id,
            name = profile.name,
            baseUrl = profile.baseUrl,
            username = profile.username,
            password = SecretField(stored = profile.hasPassword),
            accessClientId = profile.accessClientId,
            accessClientSecret = SecretField(stored = profile.hasAccessClientSecret),
            headers = profile.customHeaderNames.map { HeaderField(it, SecretField(stored = true)) },
            allowCleartext = profile.allowCleartext,
        )
    }
}
