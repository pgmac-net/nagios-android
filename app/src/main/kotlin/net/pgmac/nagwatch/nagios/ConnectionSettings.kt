// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import okhttp3.HttpUrl

/**
 * Everything needed to talk to one Nagios instance, with secrets already
 * decrypted. Lives in memory only, for as long as a request needs it.
 *
 * Deliberately not a data class: a generated `toString` would print the
 * password and tokens into any log or crash report that touched it.
 */
class ConnectionSettings(
    /** What the user typed, e.g. `https://nagios.example.org/nagios`. */
    val baseUrl: HttpUrl,
    val username: String,
    val password: String,
    val accessClientId: String? = null,
    val accessClientSecret: String? = null,
    val customHeaders: List<Pair<String, String>> = emptyList(),
    val allowCleartext: Boolean = false,
    /** The CGI directory, if already known from an earlier connect. */
    val cgiBase: HttpUrl? = null,
) {
    val hasAccessCredentials: Boolean
        get() = !accessClientId.isNullOrEmpty() && !accessClientSecret.isNullOrEmpty()

    /** The rule these settings break before any request is attempted, if any. */
    fun policyViolation(): NagiosError? = policyViolationFor(isHttps = baseUrl.isHttps)

    internal fun policyViolationFor(isHttps: Boolean): NagiosError? = when {
        isHttps -> null
        !allowCleartext -> NagiosError.CleartextRefused
        hasAccessCredentials -> NagiosError.AccessOverCleartext
        else -> null
    }

    override fun toString(): String = "ConnectionSettings(baseUrl=${baseUrl.redact()}, username=<redacted>, " +
        "password=<redacted>, access=${if (hasAccessCredentials) "<redacted>" else "none"}, " +
        "customHeaders=${customHeaders.size}, allowCleartext=$allowCleartext)"
}
