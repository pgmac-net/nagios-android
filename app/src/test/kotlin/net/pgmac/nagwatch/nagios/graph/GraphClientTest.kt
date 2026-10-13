// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.ok
import net.pgmac.nagwatch.nagios.param
import net.pgmac.nagwatch.nagios.path
import net.pgmac.nagwatch.nagios.respond
import net.pgmac.nagwatch.nagios.status
import net.pgmac.nagwatch.nagios.testFactory
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Asking a server for a graph, and what each kind of answer comes to. */
class GraphClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val day = GraphRequest("web01", "Disk /", GraphPeriod.DAY, GraphSize(360, 120))
    private val graph = Captures.bytes("graph_day_360x120.png")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `a graph is asked for beside Nagios' own CGIs, with the profile's credentials, and comes back whole`() {
        serve { image(graph) }

        val answer = fetch()

        val picture = answer as GraphAnswer.Picture
        assertArrayEquals(graph, picture.png)
        assertEquals(441 to 175, picture.width to picture.height)
        val request = requests.single()
        assertEquals("/nagios/cgi-bin/showgraph.cgi", request.path)
        assertEquals("web01", request.param("host"))
        assertEquals("a name with a space and a slash is sent intact", "Disk /", request.param("service"))
        assertEquals("day", request.param("period"))
        assertEquals("360x120", request.param("geom"))
        assertEquals(setOf("host", "service", "period", "geom"), request.url.queryParameterNames)
        assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
        assertEquals("image/png", request.headers["Accept"])
    }

    @Test
    fun `the profile's own graph path is used when it has one`() {
        serve { image(graph) }

        fetch(graphPath = "/nagiosgraph/cgi-bin/")

        assertEquals("/nagiosgraph/cgi-bin/showgraph.cgi", requests.single().path)
    }

    @Test
    fun `a graph path that is not a path is not asked of anyone`() {
        serve { image(graph) }

        assertEquals(GraphAnswer.Unavailable(NagiosError.NotNagios), fetch(graphPath = "//elsewhere.example/cgi-bin/"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `nagiosgraph's 200 with a picture of an error is no graph`() {
        listOf("no_data.png", "no_such_host.png", "blank.png").forEach { name ->
            serve { image(Captures.bytes(name)) }
            assertEquals(name, GraphAnswer.NoGraph, fetch())
        }
        serve { image(ByteArray(0)) }
        assertEquals("headers and nothing after", GraphAnswer.NoGraph, fetch())
    }

    @Test
    fun `the content type may carry a charset, as nagiosgraph's does, and any case`() {
        serve { image(graph, type = "image/png; charset=ISO-8859-1") }
        assertTrue(fetch() is GraphAnswer.Picture)
        serve { image(graph, type = "IMAGE/PNG") }
        assertTrue(fetch() is GraphAnswer.Picture)
    }

    @Test
    fun `a 200 that is not a PNG means something else is at that address`() {
        serve { ok("""{"result": {"type_code": 0}}""") }
        assertEquals(GraphAnswer.NotThere, fetch())
        serve { image(graph, type = "text/html") }
        assertEquals("a PNG's bytes under another name are not trusted either", GraphAnswer.NotThere, fetch())
        serve { image(graph, type = "image/jpeg") }
        assertEquals(GraphAnswer.NotThere, fetch())
        serve { MockResponse.Builder().code(200).body(Buffer().write(graph)).build() }
        assertEquals("no type at all", GraphAnswer.NotThere, fetch())
    }

    @Test
    fun `no such page, or not for this user, settles it for the whole profile`() {
        serve { status(404, "Not Found") }
        assertEquals(GraphAnswer.NotThere, fetch())
        serve { status(403, "Forbidden") }
        assertEquals(GraphAnswer.NotThere, fetch())
    }

    @Test
    fun `a failure that may pass is said to be one`() {
        serve { status(500, "Internal Server Error") }
        assertEquals(GraphAnswer.Unavailable(NagiosError.Http(500)), fetch())
        serve { status(401, "", "WWW-Authenticate" to "Basic realm=\"Nagios Access\"") }
        assertEquals(GraphAnswer.Unavailable(NagiosError.BadCredentials), fetch())
        serve { status(302, "", "Location" to "https://elsewhere.example/login") }
        assertTrue(
            "a redirect is not followed",
            fetch().let {
                it is GraphAnswer.Unavailable &&
                    it.error is NagiosError.Redirected
            },
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun `more than a graph could be is not read, and is not shown`() {
        // A real header, so that only the size of the body can be what refuses it.
        val huge = png(441, 175) + ByteArray(GraphRules.MAX_BYTES.toInt())
        serve { image(huge) }

        assertEquals(GraphAnswer.NoGraph, fetch())
    }

    @Test
    fun `a body of exactly the most allowed is read`() {
        val largest = png(441, 175).let { it + ByteArray(GraphRules.MAX_BYTES.toInt() - it.size) }
        serve { image(largest) }

        assertEquals(GraphRules.MAX_BYTES.toInt(), (fetch() as GraphAnswer.Picture).png.size)
    }

    @Test
    fun `a server that cannot be reached is unavailable, not ruled out`() {
        server.close()

        assertTrue(fetch().let { it is GraphAnswer.Unavailable && it.error is NagiosError.Unreachable })
    }

    @Test
    fun `cleartext the profile has not allowed is refused before anything is sent`() {
        serve { image(graph) }

        assertEquals(GraphAnswer.Unavailable(NagiosError.CleartextRefused), fetch(allowCleartext = false))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `nagiosgraph's own page is beside its pictures, and a host has none`() {
        val client = client()

        assertEquals(
            "${server.url("/nagios/cgi-bin/show.cgi")}?host=web01&service=Disk%20%2F",
            runBlocking { client.pageUrl(ObjectRef("web01", "Disk /")) },
        )
        assertNull(runBlocking { client.pageUrl(ObjectRef("web01")) })
        assertEquals(
            "${server.url("/nagiosgraph/cgi-bin/show.cgi")}?host=web01&service=Load",
            runBlocking { client(graphPath = "/nagiosgraph/cgi-bin").pageUrl(ObjectRef("web01", "Load")) },
        )
    }

    private fun fetch(graphPath: String = "", allowCleartext: Boolean = true): GraphAnswer =
        runBlocking { client(graphPath, allowCleartext).fetch(day) }

    private fun client(graphPath: String = "", allowCleartext: Boolean = true): GraphClient =
        testFactory().createGraphs(
            ConnectionSettings(
                baseUrl = server.url("/nagios"),
                username = "nagwatch",
                password = "s3cr3t-p4ss",
                allowCleartext = allowCleartext,
                cgiBase = server.url("/nagios/cgi-bin/"),
                graphPath = graphPath,
            ),
        )

    private fun serve(answer: () -> MockResponse) {
        requests.clear()
        server.respond { request ->
            requests += request
            answer()
        }
    }

    private fun image(bytes: ByteArray, type: String = "image/png; charset=ISO-8859-1"): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).body(Buffer().write(bytes)).build()
}
