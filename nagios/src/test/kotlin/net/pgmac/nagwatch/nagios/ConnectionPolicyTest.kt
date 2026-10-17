// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The rules about what may leave the device (docs/adr/0004). The manifest
 * permits cleartext, so these tests are what stands between a typo and a
 * password on the wire.
 */
class ConnectionPolicyTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `http is refused unless the profile opted in, and nothing is sent`() {
        val settings = settingsFor(server.url("/"), allowCleartext = false)

        val error = runBlocking { testFactory().create(settings).connect() }.errorOrFail()

        assertEquals(NagiosError.CleartextRefused, error)
        assertEquals("no request may reach the network", 0, server.requestCount)
    }

    @Test
    fun `http is refused on fetch as well as on connect`() {
        val settings = settingsFor(server.url("/"), allowCleartext = false, cgiBase = server.url("/cgi-bin/"))

        val error = runBlocking { testFactory().create(settings).fetchStatus() }.errorOrFail()

        assertEquals(NagiosError.CleartextRefused, error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Cloudflare Access credentials are never sent over http, even with the opt-in`() {
        val settings = settingsFor(
            server.url("/"),
            allowCleartext = true,
            accessClientId = "client-id.access",
            accessClientSecret = "client-secret",
        )

        val error = runBlocking { testFactory().create(settings).connect() }.errorOrFail()

        assertEquals(NagiosError.AccessOverCleartext, error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with the opt-in, http works and carries basic auth`() {
        server.respond { ok(Fixtures.text("programstatus.json")) }

        runBlocking {
            testFactory().create(settingsFor(server.url("/"), allowCleartext = true)).connect()
        }.valueOrFail()

        val request = server.takeRequest()
        assertEquals(Credentials.basic("nagwatch", "s3cr3t-p4ss"), request.headers["Authorization"])
        assertNull("no Access headers unless configured", request.headers["CF-Access-Client-Id"])
    }

    @Test
    fun `over https the Access service token and custom headers are sent`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.close()
        val tlsServer = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        try {
            tlsServer.respond { ok(Fixtures.text("programstatus.json")) }
            val client = testHttpClient {
                sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            }
            val settings = settingsFor(
                tlsServer.url("/").newBuilder().host("localhost").build(),
                accessClientId = "client-id.access",
                accessClientSecret = "client-secret",
                customHeaders = listOf("X-Proxy-Token" to "abc", "Authorization" to "Bearer hijack"),
            )

            runBlocking { testFactory(client).create(settings).connect() }.valueOrFail()

            val request = tlsServer.takeRequest()
            assertEquals("client-id.access", request.headers["CF-Access-Client-Id"])
            assertEquals("client-secret", request.headers["CF-Access-Client-Secret"])
            assertEquals("abc", request.headers["X-Proxy-Token"])
            assertEquals(
                "a custom header may not replace the credentials",
                Credentials.basic("nagwatch", "s3cr3t-p4ss"),
                request.headers["Authorization"],
            )
        } finally {
            tlsServer.close()
        }
    }

    @Test
    fun `settings never print their secrets`() {
        val settings = settingsFor(
            "https://nagios.example.org/nagios".toHttpUrl(),
            accessClientId = "client-id.access",
            accessClientSecret = "client-secret",
            customHeaders = listOf("X-Proxy-Token" to "header-secret"),
        )

        val printed = settings.toString()

        listOf("s3cr3t-p4ss", "nagwatch", "client-id.access", "client-secret", "header-secret").forEach { secret ->
            assertFalse("toString leaked $secret: $printed", printed.contains(secret))
        }
    }
}
