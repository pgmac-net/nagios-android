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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
        return if (error != null) NagiosResult.Failure(error) else interpretBody(response.body.string())
    }

    private fun interpretBody(text: String): NagiosResult<JsonObject> {
        val body = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val result = body?.get("result") as? JsonObject
        val code = (result?.get("type_code") as? JsonPrimitive)?.intOrNull
        return when {
            body == null || result == null || code == null -> NagiosResult.Failure(NagiosError.NotNagios)
            code == SUCCESS -> NagiosResult.Success(body)
            else -> NagiosResult.Failure(NagiosError.Api(code, result.text("type_text"), result.text("message")))
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

    private fun classify(e: IOException): NagiosError {
        val detail = e.message ?: e.javaClass.simpleName
        return when {
            e is SSLException || e.cause is CertificateException -> NagiosError.Certificate(detail)
            e is UnknownHostException -> NagiosError.Unreachable(Reason.DNS, detail)
            e is ConnectException -> NagiosError.Unreachable(Reason.REFUSED, detail)
            e is SocketTimeoutException -> NagiosError.Unreachable(Reason.TIMEOUT, detail)
            else -> NagiosError.Unreachable(Reason.OTHER, detail)
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private companion object {
        const val SUCCESS = 0
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
