// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

/**
 * A saved connection to a Nagios instance, as the UI sees it. Carries no
 * secrets: only whether each one is set. Secrets are write-only from the UI's
 * point of view and are never read back for display.
 */
data class Profile(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val username: String,
    val hasPassword: Boolean,
    val accessClientId: String,
    val hasAccessClientSecret: Boolean,
    val customHeaderNames: List<String>,
    val allowCleartext: Boolean,
) {
    val isCleartext: Boolean get() = baseUrl.trim().startsWith("http://", ignoreCase = true)
}

/** What to do with a stored secret when a profile is saved. */
sealed interface SecretInput {
    /** Leave whatever is stored. The UI shows "saved" and never the value. */
    data object Keep : SecretInput

    /** Store this value. Not a data class: it must not print itself. */
    class Replace(val value: String) : SecretInput {
        override fun toString(): String = "Replace(<redacted>)"
    }

    data object Clear : SecretInput
}

data class HeaderDraft(val name: String, val value: SecretInput)

/** A profile as submitted from the editor. [id] is null for a new profile. */
data class ProfileDraft(
    val id: Long?,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: SecretInput,
    val accessClientId: String,
    val accessClientSecret: SecretInput,
    val customHeaders: List<HeaderDraft>,
    val allowCleartext: Boolean,
)
