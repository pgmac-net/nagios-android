// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.model.ObjectRef
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's own client against nagiosgraph on the throwaway test Nagios, whose made-up checks
 * print performance data (pgmac-net/nagios-android#8). Skipped unless asked for, and never run
 * in CI. This is the check that does not depend on what a file says nagiosgraph sends:
 *
 *     scripts/test-nagios/live-test.sh
 *
 * Only reads. It still refuses anything that is not the made-up instance, so that it can never
 * be pointed at a real one by mistake.
 */
class GraphLiveTest {
    private val url = System.getenv("NAGWATCH_TEST_NAGIOS").orEmpty()
    private val factory = NagiosClientFactory(
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build(),
        Json { ignoreUnknownKeys = true },
        Dispatchers.IO,
        Clock.systemUTC(),
    )

    @Before
    fun onlyTheTestInstance() {
        assumeTrue("set NAGWATCH_TEST_NAGIOS (scripts/test-nagios/live-test.sh does)", url.isNotEmpty())
        val host = url.toHttpUrl().host
        check(host == "127.0.0.1" || host == "localhost") { "refusing: $host is not this machine" }
    }

    @Test
    fun `a service with performance data has a graph at every period and both sizes`() = runBlocking {
        val client = client()
        // nagiosgraph writes its first file a check or two after the server starts.
        val first = eventually { client.fetch(request("HTTP", GraphPeriod.DAY, INLINE)) as? GraphAnswer.Picture }
        println("live: web01/HTTP day ${INLINE.geom} -> ${first.width}x${first.height}, ${first.png.size} bytes")

        GraphPeriod.entries.forEach { period ->
            listOf(INLINE, GraphSize.FULL).forEach { size ->
                val answer = client.fetch(request("HTTP", period, size))
                assertTrue("$period at ${size.geom} was $answer", answer is GraphAnswer.Picture)
                val picture = answer as GraphAnswer.Picture
                assertTrue("larger than its plot", picture.width > size.width && picture.height > size.height)
                assertEquals(
                    "what was judged is what was sent",
                    PngHeader(picture.width, picture.height),
                    PngHeader.read(picture.png),
                )
            }
        }
    }

    @Test
    fun `a service name with a space and a slash in it is asked for intact`() = runBlocking {
        val client = client()

        val picture = eventually { client.fetch(request("Disk /", GraphPeriod.DAY, INLINE)) as? GraphAnswer.Picture }

        assertTrue(picture.width > INLINE.width)
    }

    @Test
    fun `a service with no performance data, and one that does not exist, have no graph`() = runBlocking {
        val client = client()

        assertEquals(GraphAnswer.NoGraph, client.fetch(GraphRequest("db01", "Replication", GraphPeriod.DAY, INLINE)))
        assertEquals(GraphAnswer.NoGraph, client.fetch(request("No such service", GraphPeriod.DAY, INLINE)))
        assertEquals(GraphAnswer.NoGraph, client.fetch(GraphRequest("nope", "HTTP", GraphPeriod.DAY, INLINE)))
    }

    @Test
    fun `a graph path with no nagiosgraph at it settles it for the profile`() = runBlocking {
        val answer = client(graphPath = "/nagwatch-no-such-directory/").fetch(request("HTTP", GraphPeriod.DAY, INLINE))

        assertEquals(GraphAnswer.NotThere, answer)
    }

    @Test
    fun `the image's own cgi-bin works as a graph path, and nagiosgraph's page is beside it`() = runBlocking {
        val base = url.toHttpUrl()
        val path = base.encodedPath.trimEnd('/') + "/cgi-bin/"
        val client = client(graphPath = path)

        eventually { client.fetch(request("HTTP", GraphPeriod.DAY, INLINE)) as? GraphAnswer.Picture }

        val page = checkNotNull(client.pageUrl(ObjectRef("web01", "HTTP")))
        assertTrue(page, page.endsWith("${path}show.cgi?host=web01&service=HTTP"))
    }

    private fun request(service: String, period: GraphPeriod, size: GraphSize) =
        GraphRequest("web01", service, period, size)

    private fun client(graphPath: String = ""): GraphClient = factory.createGraphs(
        ConnectionSettings(
            baseUrl = url.toHttpUrl(),
            username = "viewer",
            // Not a secret: the test Nagios' made-up users, see scripts/test-nagios/start.sh.
            password = "viewer-pw",
            allowCleartext = true,
            graphPath = graphPath,
        ),
    )

    private suspend fun <T : Any> eventually(attempt: suspend () -> T?): T {
        repeat(ATTEMPTS) {
            attempt()?.let { return it }
            delay(PAUSE_MS)
        }
        error("no graph after ${ATTEMPTS * PAUSE_MS / 1000}s: has nagiosgraph written anything?")
    }

    private companion object {
        val INLINE = GraphSize(360, 120)
        const val ATTEMPTS = 60
        const val PAUSE_MS = 5_000L
    }
}
