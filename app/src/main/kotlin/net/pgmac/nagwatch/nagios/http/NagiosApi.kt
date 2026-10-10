// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosError.Unreachable.Reason
import net.pgmac.nagwatch.nagios.NagiosResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One GET against a Nagios JSON CGI, with every failure mapped to a [NagiosError].
 *
 * Nagios reports its own errors as HTTP 200 with a non-zero `result.type_code`,
 * so the status line alone proves nothing: the body is always checked.
 */
internal class NagiosApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher,
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
) {
    /** @param cgiBase the CGI directory, ending in `/`. */
    suspend fun query(cgiBase: HttpUrl, cgi: String, params: List<Pair<String, String>>): NagiosResult<JsonObject> {
        val url = cgiBase.newBuilder().addPathSegment(cgi)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()

        return withContext(io) {
            try {
                client.newCall(request).execute().use(::interpret)
            } catch (e: PolicyViolationException) {
                NagiosResult.Failure(e.error)
            } catch (e: IOException) {
                NagiosResult.Failure(classify(e))
            }
        }
    }

    private fun interpret(response: Response): NagiosResult<JsonObject> {
        val error = httpError(response)
        // Buffer at most one byte past the limit: enough to know the body is too big
        // without holding all of whatever the server chose to send.
        val source = response.body.source()
        return when {
            error != null -> NagiosResult.Failure(error)
            source.request(maxBodyBytes + 1) -> NagiosResult.Failure(NagiosError.ResponseTooLarge)
            else -> interpretResponseBody(json, source.readUtf8())
        }
    }

    private fun httpError(response: Response): NagiosError? {
        val viaAccess = response.header("WWW-Authenticate").orEmpty().startsWith(ACCESS_CHALLENGE, ignoreCase = true)
        return when (val code = response.code) {
            HTTP_OK -> null

            in REDIRECTS -> {
                val location = response.header("Location").orEmpty()
                val target = response.request.url.resolve(location) ?: location.toHttpUrlOrNull()
                val toAccess = target?.host.orEmpty().endsWith(ACCESS_LOGIN_DOMAIN)
                if (viaAccess || toAccess) {
                    NagiosError.AccessRejected
                } else {
                    NagiosError.Redirected(target?.redact() ?: "an unknown location")
                }
            }

            HTTP_UNAUTHORIZED -> if (viaAccess) NagiosError.AccessRejected else NagiosError.BadCredentials

            HTTP_FORBIDDEN -> {
                val fromCloudflare = response.header("Server").orEmpty().equals(CLOUDFLARE, ignoreCase = true)
                if (viaAccess || fromCloudflare) NagiosError.AccessRejected else NagiosError.Forbidden
            }

            HTTP_NOT_FOUND -> NagiosError.NotNagios

            else -> NagiosError.Http(code)
        }
    }

    /**
     * A host can have several addresses (IPv6 and IPv4, say). OkHttp tries each and
     * throws the first failure with the rest attached as suppressed. The first is
     * often the least informative: "::1 refused" hides "127.0.0.1 answered with a bad
     * certificate". So every attempt is considered, and a TLS failure wins, because
     * it means something did answer.
     */
    internal fun classify(e: IOException): NagiosError {
        val attempts = listOf<Throwable>(e) + e.suppressed
        val tls = attempts.firstOrNull { it is SSLException || it.cause is CertificateException }
        val detail = (tls ?: e).let { it.message ?: it.javaClass.simpleName }
        return when {
            tls != null -> NagiosError.Certificate(detail)
            e is UnknownHostException -> NagiosError.Unreachable(Reason.DNS, detail)
            e is ConnectException -> NagiosError.Unreachable(Reason.REFUSED, detail)
            e is SocketTimeoutException -> NagiosError.Unreachable(Reason.TIMEOUT, detail)
            else -> NagiosError.Unreachable(Reason.OTHER, detail)
        }
    }

    private companion object {
        /** A page of 100 detailed records is about 170 KB; this leaves room for long plugin output. */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        val REDIRECTS = 300..399
        const val ACCESS_CHALLENGE = "Cloudflare-Access"
        const val ACCESS_LOGIN_DOMAIN = ".cloudflareaccess.com"
        const val CLOUDFLARE = "cloudflare"
    }
}
