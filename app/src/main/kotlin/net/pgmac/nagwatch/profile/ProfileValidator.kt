// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import net.pgmac.nagwatch.nagios.http.ConnectionInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Something wrong with a draft, tied to the field the editor should mark. */
enum class ProfileProblem {
    NAME_REQUIRED,
    URL_REQUIRED,
    URL_INVALID,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,

    /** `http://` without the "allow unencrypted HTTP" opt-in. */
    CLEARTEXT_NOT_ALLOWED,

    /** Cloudflare Access credentials on an `http://` URL. Never allowed, opt-in or not. */
    ACCESS_OVER_CLEARTEXT,

    /** Only one of Access client ID and secret is set; they are useless apart. */
    ACCESS_INCOMPLETE,
    HEADER_NAME_INVALID,

    /** A custom header that would override one the app sets from its own fields. */
    HEADER_NAME_RESERVED,
    HEADER_VALUE_REQUIRED,
}

/**
 * The same rules the network layer enforces (`ConnectionInterceptor`), applied
 * early so the editor can explain them instead of the user meeting a refused
 * connection later. The interceptor remains the authority.
 */
object ProfileValidator {
    /** @param stored the profile being edited, or null for a new one; decides whether "keep" secrets exist. */
    fun validate(draft: ProfileDraft, stored: Profile?): Set<ProfileProblem> = buildSet {
        if (draft.name.isBlank()) add(ProfileProblem.NAME_REQUIRED)
        if (draft.username.isBlank()) add(ProfileProblem.USERNAME_REQUIRED)
        if (!isSet(draft.password, stored?.hasPassword == true)) add(ProfileProblem.PASSWORD_REQUIRED)

        val url = draft.baseUrl.trim()
        val parsed = url.toHttpUrlOrNull()
        val hasAccessId = draft.accessClientId.isNotBlank()
        // A kept secret only counts alongside a client ID: saving without one drops the
        // stored secret, so clearing the ID is enough to remove Access from a profile.
        val keptSecretApplies = hasAccessId && stored?.hasAccessClientSecret == true
        val hasAccessSecret = isSet(draft.accessClientSecret, keptSecretApplies)
        when {
            url.isEmpty() -> add(ProfileProblem.URL_REQUIRED)

            parsed == null -> add(ProfileProblem.URL_INVALID)

            !parsed.isHttps -> {
                if (!draft.allowCleartext) add(ProfileProblem.CLEARTEXT_NOT_ALLOWED)
                if (hasAccessId || hasAccessSecret) add(ProfileProblem.ACCESS_OVER_CLEARTEXT)
            }
        }
        if (hasAccessId != hasAccessSecret) add(ProfileProblem.ACCESS_INCOMPLETE)

        addAll(headerProblems(draft.customHeaders, stored?.customHeaderNames.orEmpty()))
    }

    private fun headerProblems(headers: List<HeaderDraft>, storedNames: List<String>): Set<ProfileProblem> = buildSet {
        headers.forEach { header ->
            val name = header.name.trim()
            when {
                !HEADER_NAME.matches(name) -> add(ProfileProblem.HEADER_NAME_INVALID)

                ConnectionInterceptor.RESERVED_HEADERS.any { it.equals(name, ignoreCase = true) } ->
                    add(ProfileProblem.HEADER_NAME_RESERVED)
            }
            val alreadyStored = storedNames.any { it.equals(name, ignoreCase = true) }
            if (!isSet(header.value, alreadyStored)) add(ProfileProblem.HEADER_VALUE_REQUIRED)
        }
    }

    private fun isSet(input: SecretInput, stored: Boolean): Boolean = when (input) {
        SecretInput.Keep -> stored
        SecretInput.Clear -> false
        is SecretInput.Replace -> input.value.isNotEmpty()
    }

    /** RFC 9110 token characters: what a header name may be made of. */
    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
}
