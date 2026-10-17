// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Connecting, finding the CGI directory, and a whole poll, against fixture-backed servers. */
class NagiosClientTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `finds the CGIs directly under the base URL`() {
        serveNagiosAt("/cgi-bin/")

        val connection = connect("/").valueOrFail()

        assertEquals(server.url("/cgi-bin/"), connection.cgiBase)
        assertEquals("4.5.9", connection.server.version)
    }

    @Test
    fun `falls back to the upstream default path under the base URL`() {
        serveNagiosAt("/nagios/cgi-bin/")

        assertEquals(server.url("/nagios/cgi-bin/"), connect("/").valueOrFail().cgiBase)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a base URL that already names the Nagios directory is not doubled`() {
        serveNagiosAt("/nagios/cgi-bin/")

        assertEquals(server.url("/nagios/cgi-bin/"), connect("/nagios").valueOrFail().cgiBase)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a pasted CGI directory is used as it is`() {
        serveNagiosAt("/monitoring/cgi-bin/")

        assertEquals(server.url("/monitoring/cgi-bin/"), connect("/monitoring/cgi-bin").valueOrFail().cgiBase)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a stored CGI directory skips the search`() {
        serveNagiosAt("/deep/path/cgi-bin/")
        val settings = settingsFor(server.url("/"), cgiBase = server.url("/deep/path/cgi-bin/"))

        runBlocking { testFactory().create(settings).connect() }.valueOrFail()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a wrong password stops the search at the first path`() {
        server.respond { status(401) }

        assertEquals(NagiosError.BadCredentials, connect("/").errorOrFail())
        assertEquals("the second path would only fail the same way", 1, server.requestCount)
    }

    @Test
    fun `a poll returns every host and the services in a problem state`() {
        serveNagiosAt("/cgi-bin/")

        val snapshot = runBlocking { testFactory().create(settingsFor(server.url("/"))).fetchStatus() }.valueOrFail()

        assertEquals(FIXED_NOW, snapshot.fetchedAt)
        assertEquals(8, snapshot.hosts.size)
        assertEquals(3, snapshot.serviceProblems.size)
        assertEquals(0, snapshot.degradedCount)
    }

    @Test
    fun `a poll asks for all hosts, details of problem services, and every service's state`() {
        val requests = mutableListOf<RecordedRequest>()
        serveNagiosAt("/cgi-bin/", record = requests)

        runBlocking { testFactory().create(settingsFor(server.url("/"))).fetchStatus() }.valueOrFail()

        val problems = requests.single { it.param("query") == "servicelist" && it.param("details") == "true" }
        val states = requests.single { it.param("query") == "servicelist" && it.param("details") == null }
        val hosts = requests.single { it.param("query") == "hostlist" }
        assertEquals("warning critical unknown", problems.param("servicestatus"))
        assertEquals("every service, by name and state only", null, states.param("servicestatus"))
        assertEquals(null, hosts.param("hoststatus"))
    }

    @Test
    fun `a captured poll classifies into the problem report the real instance showed`() {
        // The capture: three hosts DOWN and three services WARNING, every one acknowledged.
        serveNagiosAt("/cgi-bin/", hostList = "hostlist_problems_details.json")

        val snapshot = runBlocking { testFactory().create(settingsFor(server.url("/"))).fetchStatus() }.valueOrFail()
        val report = ProblemClassifier.classify(snapshot)

        assertTrue(report.unhandled.isEmpty())
        assertEquals(ProblemCounts(hostsDown = 0, critical = 0, warning = 0, unknown = 0), report.counts)
        assertEquals(6, report.handled.size)
        assertTrue(report.handled.all { it.handling == Handling.ACKNOWLEDGED })
        assertEquals(3, report.handled.filterIsInstance<HostProblem>().count { it.host.state == HostState.DOWN })
    }

    @Test
    fun `a failure part way through a poll fails the poll rather than returning half a picture`() {
        server.respond { request ->
            when (request.param("query")) {
                "programstatus" -> ok(Fixtures.text("programstatus.json"))
                "hostlist" -> ok(Fixtures.text("hostlist_details.json"))
                else -> status(401)
            }
        }

        val result = runBlocking { testFactory().create(settingsFor(server.url("/"))).fetchStatus() }

        assertEquals(NagiosError.BadCredentials, result.errorOrFail())
    }

    private fun connect(basePath: String) =
        runBlocking { testFactory().create(settingsFor(server.url(basePath))).connect() }

    private fun serveNagiosAt(
        cgiPath: String,
        hostList: String = "hostlist_details.json",
        record: MutableList<RecordedRequest>? = null,
    ) = server.respond { request ->
        record?.add(request)
        if (request.path != cgiPath + "statusjson.cgi") {
            status(404, "Not Found")
        } else {
            answer(request, hostList)
        }
    }

    private fun answer(request: RecordedRequest, hostList: String): MockResponse = when (request.param("query")) {
        "programstatus" -> ok(Fixtures.text("programstatus.json"))
        "hostlist" -> ok(Fixtures.text(hostList))
        "servicelist" -> ok(Fixtures.text("servicelist_problems_details.json"))
        else -> ok(Fixtures.text("error_invalid_option.json"))
    }
}
