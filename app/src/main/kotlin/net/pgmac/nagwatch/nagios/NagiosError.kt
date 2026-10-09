// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

/**
 * Every way talking to a Nagios instance can fail, kept distinct so the UI can
 * say what actually went wrong (design section 4). Never a generic failure.
 */
sealed interface NagiosError {
    /** Cloudflare Access refused the request: missing, wrong or expired service token. */
    data object AccessRejected : NagiosError

    /** Nagios (its web server) rejected the username or password. */
    data object BadCredentials : NagiosError

    /** Authenticated, but not allowed to see this. */
    data object Forbidden : NagiosError

    /** TLS failed: untrusted, expired or mismatched certificate. */
    data class Certificate(val detail: String) : NagiosError

    /** No usable response from the instance at all. */
    data class Unreachable(val reason: Reason, val detail: String) : NagiosError {
        enum class Reason { DNS, REFUSED, TIMEOUT, OTHER }
    }

    /** Something answered, but it is not the Nagios JSON CGI. Usually a wrong URL. */
    data object NotNagios : NagiosError

    /** Nagios understood the request and reported an error of its own. */
    data class Api(val code: Int, val type: String, val message: String) : NagiosError

    /** The URL is `http://` and the profile has not opted in to unencrypted traffic. */
    data object CleartextRefused : NagiosError

    /** Cloudflare Access credentials would have been sent over `http://`. Never allowed. */
    data object AccessOverCleartext : NagiosError

    /**
     * The server redirected. Redirects are never followed: credentials are only
     * ever sent to the host the user configured.
     */
    data class Redirected(val location: String) : NagiosError

    /** An HTTP status with no more specific meaning, typically a 5xx from the CGI. */
    data class Http(val code: Int) : NagiosError {
        val isServerError: Boolean get() = code in SERVER_ERRORS
    }

    private companion object {
        val SERVER_ERRORS = 500..599
    }
}

sealed interface NagiosResult<out T> {
    data class Success<T>(val value: T) : NagiosResult<T>

    data class Failure(val error: NagiosError) : NagiosResult<Nothing>
}

internal inline fun <T, R> NagiosResult<T>.map(transform: (T) -> R): NagiosResult<R> = when (this) {
    is NagiosResult.Success -> NagiosResult.Success(transform(value))
    is NagiosResult.Failure -> this
}
