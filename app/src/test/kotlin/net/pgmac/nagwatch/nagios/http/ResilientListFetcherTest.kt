// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.Fixtures
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.errorOrFail
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.ok
import net.pgmac.nagwatch.nagios.param
import net.pgmac.nagwatch.nagios.respond
import net.pgmac.nagwatch.nagios.settingsFor
import net.pgmac.nagwatch.nagios.status
import net.pgmac.nagwatch.nagios.testApi
import net.pgmac.nagwatch.nagios.testHttpClient
import net.pgmac.nagwatch.nagios.valueOrFail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The server here behaves like the real Nagios that prompted this code: any
 * `details=true` window containing a "poisoned" record answers HTTP 500, the
 * same window without details is fine, and `start`/`count` slice one stable list.
 */
class ResilientListFetcherTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `a healthy list costs one request per page and nothing more`() {
        serve(total = 250)

        val records = fetch().valueOrFail()

        assertEquals(250, records.size)
        assertEquals(names(250), records.map { it.description })
        assertTrue(records.all { it.check.detailsAvailable })
        assertEquals("pages of 100: 0, 100, 200", 3, server.requestCount)
    }

    @Test
    fun `a list that is an exact multiple of the page size ends on an empty page`() {
        serve(total = 200)

        assertEquals(200, fetch().valueOrFail().size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `one unserialisable record is isolated and returned degraded, everything else intact`() {
        // The real case: record 163 of 220.
        serve(total = 220, poisoned = setOf(163))

        val records = fetch().valueOrFail()

        assertEquals(names(220), records.map { it.description })
        assertEquals(listOf("svc163"), records.filterNot { it.check.detailsAvailable }.map { it.description })
        assertTrue("took ${server.requestCount} requests", server.requestCount <= 3 + ISOLATION_COST)
    }

    @Test
    fun `two bad records in different pages are both isolated`() {
        serve(total = 220, poisoned = setOf(7, 163))

        val records = fetch().valueOrFail()

        assertEquals(names(220), records.map { it.description })
        assertEquals(listOf("svc007", "svc163"), records.filterNot { it.check.detailsAvailable }.map { it.description })
    }

    @Test
    fun `a bad record in the last, partial page is isolated without losing the end of the list`() {
        serve(total = 130, poisoned = setOf(129))

        val records = fetch().valueOrFail()

        assertEquals(130, records.size)
        assertEquals("svc129", records.last().description)
        assertTrue(!records.last().check.detailsAvailable)
    }

    @Test
    fun `adjacent bad records are each returned degraded`() {
        serve(total = 50, poisoned = setOf(20, 21))

        val records = fetch().valueOrFail()

        assertEquals(names(50), records.map { it.description })
        assertEquals(2, records.count { !it.check.detailsAvailable })
    }

    @Test
    fun `too many bad records gives up and reports the server error`() {
        serve(total = 100, poisoned = (0 until 100 step 12).toSet())

        assertEquals(NagiosError.Http(500), fetch().errorOrFail())
    }

    @Test
    fun `the search stays within its request budget`() {
        serve(total = 100, poisoned = (0 until 100 step 12).toSet())

        fetch()

        val budget = 1 + ResilientListFetcher.MAX_EXTRA_REQUESTS
        assertTrue("took ${server.requestCount} requests, budget $budget", server.requestCount <= budget)
    }

    @Test
    fun `a server that fails without details too is not searched`() {
        server.respond { status(500, Fixtures.SERVER_ERROR_HTML) }

        assertEquals(NagiosError.Http(500), fetch().errorOrFail())
        assertEquals("the failed page, then one probe without details", 2, server.requestCount)
    }

    @Test
    fun `an error that is not a server error is returned at once`() {
        server.respond { status(401) }

        assertEquals(NagiosError.BadCredentials, fetch().errorOrFail())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server that never ends the list is cut off rather than followed forever`() {
        // Ignores `start`: every page is full, so no short page ever arrives.
        server.respond { request -> page(request.windowIgnoringStart(), total = 1_000, poisoned = emptySet()) }

        val result = fetch(pageSize = 10, maxRecords = 50)

        assertEquals(NagiosError.ResponseTooLarge, result.errorOrFail())
        assertEquals("five pages fill the ceiling; the sixth is refused", 6, server.requestCount)
    }

    @Test
    fun `a response holding more records than were asked for is refused at once`() {
        // Ignores `count`: the whole list comes back for every request.
        server.respond { request ->
            page(Window(start = 0, count = 1_000, details = request.param("details") == "true"), 1_000, emptySet())
        }

        val result = fetch(pageSize = 10)

        assertEquals(NagiosError.ResponseTooLarge, result.errorOrFail())
        assertEquals("refused on the first response, nothing kept", 1, server.requestCount)
    }

    @Test
    fun `a list of exactly the ceiling is returned, one more is not`() {
        serve(total = 50)
        assertEquals(50, fetch(pageSize = 10, maxRecords = 50).valueOrFail().size)

        serve(total = 51)
        assertEquals(NagiosError.ResponseTooLarge, fetch(pageSize = 10, maxRecords = 50).errorOrFail())
    }

    @Test
    fun `a list under the ceiling that ends properly is still returned`() {
        serve(total = 45)

        assertEquals(45, fetch(pageSize = 10, maxRecords = 50).valueOrFail().size)
    }

    @Test
    fun `filters are sent with every request, and states are requested as words`() {
        serve(total = 3)

        fetch(filters = listOf("servicestatus" to "warning critical unknown")).valueOrFail()

        val request = server.takeRequest()
        assertEquals("warning critical unknown", request.param("servicestatus"))
        assertEquals("enumerate", request.param("formatoptions"))
        assertEquals("true", request.param("details"))
        assertEquals("servicelist", request.param("query"))
    }

    private fun fetch(
        filters: List<Pair<String, String>> = emptyList(),
        pageSize: Int = ResilientListFetcher.PAGE_SIZE,
        maxRecords: Int = ResilientListFetcher.MAX_RECORDS,
    ): NagiosResult<List<ServiceStatus>> {
        val settings: ConnectionSettings = settingsFor(server.url("/"))
        val client = testHttpClient { addInterceptor(ConnectionInterceptor(settings)) }
        return runBlocking {
            ResilientListFetcher(testApi(client), pageSize = pageSize, maxRecords = maxRecords)
                .fetchAll(server.url("/cgi-bin/"), "servicelist", filters, StatusParser::services)
        }
    }

    private fun serve(total: Int, poisoned: Set<Int> = emptySet()) = server.respond { request ->
        page(request, total, poisoned)
    }

    private fun page(request: RecordedRequest, total: Int, poisoned: Set<Int>): MockResponse = page(
        Window(
            start = request.param("start")?.toInt() ?: 0,
            count = request.param("count")?.toInt() ?: total,
            details = request.param("details") == "true",
        ),
        total,
        poisoned,
    )

    private fun RecordedRequest.windowIgnoringStart() =
        Window(start = 0, count = param("count")?.toInt() ?: 0, details = param("details") == "true")

    private data class Window(val start: Int, val count: Int, val details: Boolean)

    private fun page(requested: Window, total: Int, poisoned: Set<Int>): MockResponse {
        val (start, count, details) = requested
        val window = (start until minOf(start + count, total))
        if (details && window.any { it in poisoned }) return status(500, Fixtures.SERVER_ERROR_HTML)

        val services = window.joinToString(",") { index ->
            val entry = if (details) """{"status": "ok", "current_attempt": 1, "max_attempts": 3}""" else "\"ok\""
            "\"${name(index)}\": $entry"
        }
        return ok(
            """{"result": {"type_code": 0, "type_text": "Success"}, "data": {"servicelist": {"web01": {$services}}}}""",
        )
    }

    private fun name(index: Int) = "svc%03d".format(index)

    private fun names(total: Int) = (0 until total).map(::name)

    private companion object {
        /** One probe without details, a binary search of a page of 100, and the final state-only fetch. */
        const val ISOLATION_COST = 1 + 14 + 1
    }
}
