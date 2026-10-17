// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.Fixtures
import net.pgmac.nagwatch.nagios.TEST_JSON
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.nagios.model.ObjectRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Groups as `objectjson.cgi` describes them (pgmac-net/nagios-android#82). */
class ObjectParserTest {
    @Test
    fun `host groups have their name, alias and members, and a group with no alias goes by its name`() {
        val groups = ObjectParser.hostGroups(Fixtures.json("objectjson_hostgrouplist.json")).associateBy { it.name }

        assertEquals(setOf("web-servers", "databases", "linux", "empty"), groups.keys)
        assertEquals("Web servers", groups.getValue("web-servers").label)
        assertEquals(listOf("host01", "host02", "host03"), groups.getValue("web-servers").members)
        assertEquals("empty", groups.getValue("empty").label)
        assertTrue(groups.getValue("empty").members.isEmpty())
    }

    @Test
    fun `service group members are services on their hosts`() {
        val groups = ObjectParser.serviceGroups(Fixtures.json("objectjson_servicegrouplist.json")).associateBy {
            it.name
        }

        assertEquals(
            listOf(ObjectRef("host01", "Disk /"), ObjectRef("host02", "Disk /"), ObjectRef("host04", "Disk /var")),
            groups.getValue("storage").members,
        )
        assertEquals("Network checks", groups.getValue("network").label)
    }

    @Test
    fun `a member of the wrong shape is left out, not the group, and a repeated member is kept once`() {
        val body = parse(
            """{"data": {"hostgrouplist": {"g": {"group_name": "g", "members": ["host01", 7, {"x": 1}, "", "host01"]}},
               "servicegrouplist": {"s": {"members": [{"host_name": "host01"}, "host02",
               {"host_name": "host01", "service_description": "PING"}]}}}}""",
        )

        assertEquals(listOf("host01"), ObjectParser.hostGroups(body).single().members)
        val services = ObjectParser.serviceGroups(body).single()
        assertEquals("s", services.name)
        assertEquals(listOf(ObjectRef("host01", "PING")), services.members)
    }

    @Test
    fun `no list, or a list of the wrong shape, is no groups`() {
        assertTrue(ObjectParser.hostGroups(parse("""{"data": {}}""")).isEmpty())
        assertTrue(ObjectParser.hostGroups(parse("""{"data": {"hostgrouplist": {"g": "up"}}}""")).isEmpty())
        assertTrue(ObjectParser.serviceGroups(parse("""{"data": {"servicegrouplist": []}}""")).isEmpty())
    }

    @Test
    fun `hosts have their parents, a host is never its own, and odd entries are skipped`() {
        val hosts = ObjectParser.hostConfigs(Fixtures.json("objectjson_hostlist.json")).associateBy { it.name }

        assertEquals(listOf("host02", "host03"), hosts.getValue("host04").parents)
        assertTrue(hosts.getValue("host01").parents.isEmpty())

        val odd =
            parse(
                """{"data": {"hostlist": {"host01": {"name": "host01", "parent_hosts": ["host01", 3, "host02"]},
            "host02": "up"}}}""",
            )
        assertEquals(listOf(HostConfig("host01", listOf("host02"))), ObjectParser.hostConfigs(odd))
    }

    private fun parse(text: String) = TEST_JSON.parseToJsonElement(text) as JsonObject
}
