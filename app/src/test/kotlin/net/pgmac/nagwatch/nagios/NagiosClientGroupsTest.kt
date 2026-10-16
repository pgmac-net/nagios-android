// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.model.ObjectConfig
import net.pgmac.nagwatch.nagios.model.ObjectRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Groups are read from `objectjson.cgi`, the configuration CGI (pgmac-net/nagios-android#82). */
class NagiosClientGroupsTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `host groups are asked of the object CGI, in full`() {
        serve()

        val groups = runBlocking { client().fetchHostGroups() }.valueOrFail()

        val request = requests.single()
        assertEquals("/cgi-bin/objectjson.cgi", request.url.encodedPath)
        assertEquals("hostgrouplist", request.param("query"))
        assertEquals("true", request.param("details"))
        assertEquals(4, groups.size)
    }

    @Test
    fun `service groups likewise`() {
        serve()

        val groups = runBlocking { client().fetchServiceGroups() }.valueOrFail()

        assertEquals("servicegrouplist", requests.single().param("query"))
        assertEquals(2, groups.size)
    }

    @Test
    fun `hosts' parents come from the host list of the object CGI`() {
        serve()

        val hosts = runBlocking { client().fetchHostConfigs() }.valueOrFail()

        assertEquals("hostlist", requests.single().param("query"))
        assertEquals("/cgi-bin/objectjson.cgi", requests.single().url.encodedPath)
        assertEquals(5, hosts.size)
    }

    @Test
    fun `a host's definition is asked of the object CGI by name`() {
        server.respond { request ->
            requests += request
            ok(
                """{"result": {"type_code": 0}, "data": {"host": {"name": "host01", "alias": "First",
                "address": "203.0.113.1", "notes": "n", "notes_url": "/wiki/host01", "action_url": ""}}}""",
            )
        }

        val config = runBlocking { client().fetchObjectConfig(ObjectRef("host01")) }.valueOrFail()

        val request = requests.single()
        assertEquals("/cgi-bin/objectjson.cgi", request.url.encodedPath)
        assertEquals("host", request.param("query"))
        assertEquals("host01", request.param("hostname"))
        assertEquals(ObjectConfig("host01", "First", "203.0.113.1", null, "n", "/wiki/host01", ""), config)
    }

    @Test
    fun `a service's definition comes with its host's, for the macros`() {
        server.respond { request ->
            requests += request
            when (request.param("query")) {
                "host" -> ok(
                    """{"result": {"type_code": 0}, "data": {"host": {"name": "host01", "address": "203.0.113.1"}}}""",
                )

                else -> ok(
                    """{"result": {"type_code": 0}, "data": {"service": {"host_name": "host01",
                    "description": "Disk /", "notes_url": "https://wiki.example.org/x"}}}""",
                )
            }
        }

        val config = runBlocking { client().fetchObjectConfig(ObjectRef("host01", "Disk /")) }.valueOrFail()

        assertEquals(listOf("host", "service"), requests.map { it.param("query") })
        assertEquals("Disk /", requests.last().param("servicedescription"))
        assertEquals("203.0.113.1", config.hostAddress)
        assertEquals("https://wiki.example.org/x", config.notesUrl)
    }

    @Test
    fun `a user who may not read the configuration gets an error, not an exception`() {
        server.respond { status(403) }

        val error = runBlocking { client().fetchHostGroups() }.errorOrFail()

        assertEquals(NagiosError.Forbidden, error)
    }

    private fun client(): NagiosClient =
        testFactory().create(settingsFor(server.url("/"), cgiBase = server.url("/cgi-bin/")))

    private fun serve() = server.respond { request ->
        requests += request
        when (request.param("query")) {
            "hostgrouplist" -> ok(Fixtures.text("objectjson_hostgrouplist.json"))
            "servicegrouplist" -> ok(Fixtures.text("objectjson_servicegrouplist.json"))
            "hostlist" -> ok(Fixtures.text("objectjson_hostlist.json"))
            else -> status(404)
        }
    }
}
