// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.pgmac.nagwatch.nagios.NagiosError.Unreachable.Reason
import net.pgmac.nagwatch.nagios.http.ConnectionInterceptor
import net.pgmac.nagwatch.nagios.http.NagiosApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Each way a connection can fail must come back as its own [NagiosError] (design section 4). */
class ErrorClassificationTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `401 from the web server means bad credentials`() {
        server.respond { status(401, "", "WWW-Authenticate" to "Basic realm=\"Nagios Access\"") }

        assertEquals(NagiosError.BadCredentials, connectError())
    }

    @Test
    fun `a redirect to the Cloudflare Access login means Access rejected the request`() {
        // Shape captured from a real Access-protected Nagios, without a service token.
        server.respond {
            status(
                302,
                "",
                "Location" to "https://team.cloudflareaccess.com/cdn-cgi/access/login/nagios.example.org?kid=abc",
                "WWW-Authenticate" to "Cloudflare-Access resource_metadata=\"https://nagios.example.org/x\"",
                "Server" to "cloudflare",
            )
        }

        assertEquals(NagiosError.AccessRejected, connectError())
    }

    @Test
    fun `an Access login redirect is recognised by its destination alone`() {
        server.respond { status(302, "", "Location" to "https://team.cloudflareaccess.com/cdn-cgi/access/login/x") }

        assertEquals(NagiosError.AccessRejected, connectError())
    }

    @Test
    fun `403 from Cloudflare means Access rejected the service token`() {
        server.respond { status(403, "Forbidden", "Server" to "cloudflare") }

        assertEquals(NagiosError.AccessRejected, connectError())
    }

    @Test
    fun `403 from the origin is forbidden, not an Access problem`() {
        server.respond { status(403, "Forbidden", "Server" to "Apache") }

        assertEquals(NagiosError.Forbidden, connectError())
    }

    @Test
    fun `any other redirect is reported and never followed`() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            server.respond { status(301, "", "Location" to elsewhere.url("/nagios/").toString()) }

            val error = connectError()

            assertTrue("was $error", error is NagiosError.Redirected)
            assertEquals("credentials must not follow a redirect", 0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `no Nagios CGI at any candidate path means not Nagios`() {
        server.respond { status(404, "Not Found") }

        assertEquals(NagiosError.NotNagios, connectError())
        assertEquals("both candidate paths are tried", 2, server.requestCount)
    }

    @Test
    fun `a web page answering 200 is not Nagios`() {
        server.respond { status(200, Fixtures.SOME_HTML, "Content-Type" to "text/html") }

        assertEquals(NagiosError.NotNagios, connectError())
    }

    @Test
    fun `JSON without a Nagios result block is not Nagios`() {
        server.respond { ok("""{"hello": "world"}""") }

        assertEquals(NagiosError.NotNagios, connectError())
    }

    @Test
    fun `an error reported by Nagios inside a 200 response is surfaced with its message`() {
        server.respond { ok(Fixtures.text("error_invalid_option.json")) }

        assertEquals(
            NagiosError.Api(6, "Option Value Invalid", "The query option value 'nonsense' is invalid."),
            connectError(),
        )
    }

    @Test
    fun `a crashing CGI is an HTTP server error`() {
        server.respond { status(500, Fixtures.SERVER_ERROR_HTML) }

        val error = connectError()

        assertEquals(NagiosError.Http(500), error)
        assertTrue((error as NagiosError.Http).isServerError)
    }

    @Test
    fun `a response over the size limit is refused without being read into memory whole`() {
        server.respond { ok("""{"result": {"type_code": 0}, "padding": "${"x".repeat(4_096)}"}""") }
        val settings = settingsFor(server.url("/"))
        val client = testHttpClient { addInterceptor(ConnectionInterceptor(settings)) }
        val api = NagiosApi(client, TEST_JSON, Dispatchers.IO, maxBodyBytes = 1_024)

        val result = runBlocking { api.query(server.url("/cgi-bin/"), "statusjson.cgi", emptyList()) }

        assertEquals(NagiosError.ResponseTooLarge, result.errorOrFail())
    }

    @Test
    fun `a response just under the size limit is read normally`() {
        server.respond { ok("""{"result": {"type_code": 0}}""") }
        val settings = settingsFor(server.url("/"))
        val client = testHttpClient { addInterceptor(ConnectionInterceptor(settings)) }
        val api = NagiosApi(client, TEST_JSON, Dispatchers.IO, maxBodyBytes = 1_024)

        runBlocking { api.query(server.url("/cgi-bin/"), "statusjson.cgi", emptyList()) }.valueOrFail()
    }

    @Test
    fun `nothing listening is unreachable, refused`() {
        val url = server.url("/")
        server.close()

        val error = runBlocking { testFactory().create(settingsFor(url)).connect() }.errorOrFail()

        assertEquals(Reason.REFUSED, (error as NagiosError.Unreachable).reason)
    }

    @Test
    fun `a name that does not resolve is unreachable, DNS`() {
        val settings = settingsFor("https://nagwatch-does-not-exist.invalid/".toHttpUrl())

        val error = runBlocking { testFactory().create(settings).connect() }.errorOrFail()

        assertEquals(Reason.DNS, (error as NagiosError.Unreachable).reason)
    }

    @Test
    fun `a server that never answers is unreachable, timeout`() {
        server.respond { MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("late").build() }
        val impatient = testHttpClient { readTimeout(Duration.ofMillis(200)) }

        val error = runBlocking { testFactory(impatient).create(settingsFor(server.url("/"))).connect() }.errorOrFail()

        assertEquals(Reason.TIMEOUT, (error as NagiosError.Unreachable).reason)
    }

    @Test
    fun `an untrusted certificate is a certificate error, not unreachable`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.close()
        val tlsServer = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        try {
            val url = tlsServer.url("/").newBuilder().host("localhost").build()

            val error = runBlocking { testFactory().create(settingsFor(url)).connect() }.errorOrFail()

            assertTrue("was $error", error is NagiosError.Certificate)
        } finally {
            tlsServer.close()
        }
    }

    @Test
    fun `a certificate failure on a later address is not hidden by a refusal on the first`() {
        // What OkHttp throws for a dual-stack host: the first attempt, others suppressed.
        val firstAttempt = ConnectException("Failed to connect to localhost/[0:0:0:0:0:0:0:1]:8443")
        firstAttempt.addSuppressed(SSLHandshakeException("PKIX path building failed"))

        val error = testApi().classify(firstAttempt)

        assertEquals(NagiosError.Certificate("PKIX path building failed"), error)
    }

    @Test
    fun `a refusal on every address stays unreachable`() {
        val firstAttempt = ConnectException("Failed to connect to [::1]:8443")
        firstAttempt.addSuppressed(ConnectException("Failed to connect to 127.0.0.1:8443"))

        assertEquals(Reason.REFUSED, (testApi().classify(firstAttempt) as NagiosError.Unreachable).reason)
    }

    private fun connectError(): NagiosError =
        runBlocking { testFactory().create(settingsFor(server.url("/"))).connect() }.errorOrFail()
}
