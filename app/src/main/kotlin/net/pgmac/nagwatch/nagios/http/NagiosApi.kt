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
import okhttp3.FormBody
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

    /**
     * One GET of an HTML page from a CGI: the forms `cmd.cgi` serves. Reading a form changes
     * nothing on the server.
     */
    suspend fun page(cgiBase: HttpUrl, cgi: String, params: List<Pair<String, String>>): NagiosResult<String> {
        val url = cgiBase.newBuilder().addPathSegment(cgi)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        return withContext(io) {
            try {
                client.newCall(request).execute().use(::readPage)
            } catch (e: PolicyViolationException) {
                NagiosResult.Failure(e.error)
            } catch (e: IOException) {
                NagiosResult.Failure(classify(e))
            }
        }
    }

    /** What is known after posting a form that changes something. */
    sealed interface Submission {
        /** The server answered with a page. Whether it did what was asked is in the page. */
        class Answered(val html: String) : Submission

        /** The request certainly did not reach the CGI. Nothing changed. */
        class NotSent(val error: NagiosError) : Submission

        /** The request may have reached the CGI, and there is no telling what it did. */
        class Unknown(val error: NagiosError) : Submission
    }

    /**
     * One POST of a form to a CGI that acts on it.
     *
     * Sent once. The client this is built on must have retries switched off (see
     * `NagiosClientFactory.createCommands`): a request that changes something must not be
     * repeated because a connection wobbled. When it cannot be known whether the server got
     * the request, that is what is said.
     */
    suspend fun submit(cgiBase: HttpUrl, cgi: String, form: List<Pair<String, String>>): Submission {
        val url = cgiBase.newBuilder().addPathSegment(cgi).build()
        val body = FormBody.Builder(Charsets.UTF_8).apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder().url(url).header("Accept", "text/html").post(body).build()
        return withContext(io) {
            try {
                client.newCall(request).execute().use(::readSubmission)
            } catch (e: PolicyViolationException) {
                // Refused by this app before anything was put on the wire.
                Submission.NotSent(e.error)
            } catch (e: IOException) {
                val error = classify(e)
                if (error.meansNothingWasSent()) Submission.NotSent(error) else Submission.Unknown(error)
            }
        }
    }

    private fun readSubmission(response: Response): Submission {
        val error = httpError(response)
        return when {
            // A server error may have come from the CGI itself, after it acted.
            error is NagiosError.Http && error.isServerError -> Submission.Unknown(error)

            // Anything else below 500 was turned away before the CGI ran: bad credentials, a redirect, no such page.
            error != null -> Submission.NotSent(error)

            // It ran, and answered with more than a result page could be.
            response.body.source().request(MAX_PAGE_BYTES + 1) -> Submission.Unknown(NagiosError.ResponseTooLarge)

            else -> Submission.Answered(response.body.source().readUtf8())
        }
    }

    private fun readPage(response: Response): NagiosResult<String> {
        val error = httpError(response)
        val source = response.body.source()
        return when {
            error != null -> NagiosResult.Failure(error)
            source.request(MAX_PAGE_BYTES + 1) -> NagiosResult.Failure(NagiosError.ResponseTooLarge)
            else -> NagiosResult.Success(source.readUtf8())
        }
    }

    /**
     * True for failures that happen before a request is written: the name did not resolve,
     * nothing was listening, or TLS was never established. A timeout is not one of them: the
     * request may be sitting with the server.
     */
    private fun NagiosError.meansNothingWasSent(): Boolean = when (this) {
        is NagiosError.Certificate -> true
        is NagiosError.Unreachable -> reason == Reason.DNS || reason == Reason.REFUSED
        else -> false
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

        /** A command form or result page is a few kilobytes. */
        const val MAX_PAGE_BYTES = 512L * 1024
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
