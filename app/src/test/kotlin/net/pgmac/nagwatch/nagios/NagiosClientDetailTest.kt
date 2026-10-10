// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The calls a detail screen makes, and the poll's state-only list of every service. */
class NagiosClientDetailTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `a service is asked for by host and description`() {
        serve("service" to "service_detail.json")

        val service = runBlocking { client().fetchService(ObjectRef("host12", "Disk /var")) }.valueOrFail()

        val request = requests.single()
        assertEquals("service", request.param("query"))
        assertEquals("host12", request.param("hostname"))
        assertEquals("a name with a space and a slash is sent intact", "Disk /var", request.param("servicedescription"))
        assertEquals("enumerate", request.param("formatoptions"))
        assertEquals(ServiceState.OK, service.state)
    }

    @Test
    fun `a host is asked for by name, with no service`() {
        serve("host" to "host_detail.json")

        val host = runBlocking { client().fetchHost("host12") }.valueOrFail()

        assertEquals("host12", requests.single().param("hostname"))
        assertNull(requests.single().param("servicedescription"))
        assertEquals("host12", host.name)
    }

    @Test
    fun `an object Nagios does not have is reported, not thrown`() {
        server.respond { ok("""{"result": {"type_code": 0}, "data": {}}""") }

        val error = runBlocking { client().fetchService(ObjectRef("host12", "Gone")) }.errorOrFail()

        assertTrue("was $error", error is NagiosError.Api)
    }

    @Test
    fun `the record that crashes the CGI fails as a server error, for the screen to degrade`() {
        server.respond { status(500, Fixtures.SERVER_ERROR_HTML) }

        val error = runBlocking { client().fetchService(ObjectRef("host12", "Odd")) }.errorOrFail()

        assertEquals(NagiosError.Http(500), error)
    }

    @Test
    fun `a host's comments are its own, although the server sends its services' too`() {
        serve("commentlist" to "commentlist_by_host.json")

        val comments = runBlocking { client().fetchComments(ObjectRef("host12")) }.valueOrFail()

        assertEquals(listOf(4862L), comments.map { it.id })
        assertEquals("true", requests.single().param("details"))
        assertNull("a host query names no service", requests.single().param("servicedescription"))
    }

    @Test
    fun `a service's comments are asked for by host and service`() {
        serve("commentlist" to "commentlist_by_service.json")

        val comments = runBlocking { client().fetchComments(ObjectRef("host12", "Memory")) }.valueOrFail()

        assertEquals(listOf(649L), comments.map { it.id })
        assertEquals("Memory", requests.single().param("servicedescription"))
    }

    @Test
    fun `downtimes are filtered to the object`() {
        serve("downtimelist" to "downtimelist_synthetic.json")

        assertEquals(
            listOf(7L),
            runBlocking {
                client().fetchDowntimes(ObjectRef("host12"))
            }.valueOrFail().map { it.id },
        )
        assertEquals(
            listOf(9L),
            runBlocking { client().fetchDowntimes(ObjectRef("host12", "Swap")) }.valueOrFail().map { it.id },
        )
    }

    @Test
    fun `comments failing does not take anything else with it`() {
        server.respond { request ->
            if (request.param("query") ==
                "commentlist"
            ) {
                status(403, "Forbidden")
            } else {
                ok(Fixtures.text("service_detail.json"))
            }
        }
        val client = client()

        assertEquals(
            NagiosError.Forbidden,
            runBlocking {
                client.fetchComments(ObjectRef("host12", "Memory"))
            }.errorOrFail(),
        )
        runBlocking { client.fetchService(ObjectRef("host12", "Memory")) }.valueOrFail()
    }

    @Test
    fun `a very long comment history is capped`() {
        val many = (1..NagiosClient.MAX_ANNOTATIONS + 50).joinToString(",") { id ->
            "\"$id\": {\"comment_id\": $id, \"host_name\": \"web01\", " +
                "\"entry_type\": \"acknowledgement\", \"entry_time\": ${id}000}"
        }
        server.respond { ok("""{"result": {"type_code": 0}, "data": {"commentlist": {$many}}}""") }

        val comments = runBlocking { client().fetchComments(ObjectRef("web01")) }.valueOrFail()

        assertEquals(NagiosClient.MAX_ANNOTATIONS, comments.size)
        assertEquals("the newest are the ones kept", (NagiosClient.MAX_ANNOTATIONS + 50).toLong(), comments.first().id)
    }

    @Test
    fun `a poll also lists every service by name and state`() {
        server.respond { request ->
            requests += request
            when {
                request.param("query") == "hostlist" -> ok(Fixtures.text("hostlist_details.json"))
                request.param("details") == "true" -> ok(Fixtures.text("servicelist_problems_details.json"))
                else -> ok(STATES)
            }
        }

        val snapshot = runBlocking { client().fetchStatus() }.valueOrFail()

        assertEquals(
            listOf("PING" to ServiceState.OK, "Disk /" to ServiceState.CRITICAL, "New" to ServiceState.PENDING),
            snapshot.serviceStates.map { it.description to it.state },
        )
        assertEquals("the detailed problem list is still there", 3, snapshot.serviceProblems.size)
    }

    @Test
    fun `a failure of the state list fails the poll, rather than returning half a picture`() {
        server.respond { request ->
            when {
                request.param("query") == "hostlist" -> ok(Fixtures.text("hostlist_details.json"))
                request.param("details") == "true" -> ok(Fixtures.text("servicelist_problems_details.json"))
                else -> status(500, Fixtures.SERVER_ERROR_HTML)
            }
        }

        assertEquals(NagiosError.Http(500), runBlocking { client().fetchStatus() }.errorOrFail())
    }

    private fun client(): NagiosClient =
        testFactory().create(settingsFor(server.url("/"), cgiBase = server.url("/cgi-bin/")))

    /** Answers one kind of query from a fixture and records what was asked. */
    private fun serve(vararg answers: Pair<String, String>) = server.respond { request ->
        requests += request
        answers.toMap()[request.param("query")]?.let { ok(Fixtures.text(it)) } ?: status(404)
    }

    private companion object {
        const val STATES = """{"result": {"type_code": 0}, "data": {"servicelist": {
            "web01": {"PING": "ok", "Disk /": "critical"}, "web02": {"New": "pending"}, "web03": {}}}}"""
    }
}
