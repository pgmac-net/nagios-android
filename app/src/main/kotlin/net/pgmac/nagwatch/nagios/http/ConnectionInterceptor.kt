// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.io.IOException
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/** Thrown from inside OkHttp when a request would break a connection rule. */
internal class PolicyViolationException(val error: NagiosError) : IOException("Request refused: $error")

/**
 * The single choke point for what leaves the device.
 *
 * The manifest has to permit cleartext app-wide, because Android cannot allow
 * it per host chosen at runtime (docs/adr/0004). So this interceptor is what
 * actually enforces the rules:
 *
 * - `http://` only when the profile opted in;
 * - Cloudflare Access credentials never over `http://`;
 * - credentials only to the origin the user configured.
 */
internal class ConnectionInterceptor(private val settings: ConnectionSettings) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val base = settings.baseUrl

        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) {
            throw PolicyViolationException(NagiosError.Redirected(url.redact()))
        }
        settings.policyViolationFor(isHttps = url.isHttps)?.let { throw PolicyViolationException(it) }

        val builder = request.newBuilder()
        settings.customHeaders
            .filterNot { (name, _) -> RESERVED_HEADERS.any { it.equals(name, ignoreCase = true) } }
            .forEach { (name, value) -> builder.header(name, value) }
        builder.header(AUTHORIZATION, Credentials.basic(settings.username, settings.password, Charsets.UTF_8))
        if (settings.hasAccessCredentials) {
            builder.header(ACCESS_CLIENT_ID, settings.accessClientId.orEmpty())
            builder.header(ACCESS_CLIENT_SECRET, settings.accessClientSecret.orEmpty())
        }
        return chain.proceed(builder.build())
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
        const val ACCESS_CLIENT_ID = "CF-Access-Client-Id"
        const val ACCESS_CLIENT_SECRET = "CF-Access-Client-Secret"

        /** Custom headers may not override these: they are set from dedicated fields. */
        val RESERVED_HEADERS = listOf(AUTHORIZATION, ACCESS_CLIENT_ID, ACCESS_CLIENT_SECRET, "Host")
    }
}
