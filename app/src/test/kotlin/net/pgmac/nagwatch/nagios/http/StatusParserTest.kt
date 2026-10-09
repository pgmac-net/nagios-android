// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.Fixtures
import net.pgmac.nagwatch.nagios.TEST_JSON
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.StateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing, checked against sanitised captures from a real Nagios Core 4.5.9. */
class StatusParserTest {
    @Test
    fun `server info comes from programstatus`() {
        val info = StatusParser.serverInfo(Fixtures.json("programstatus.json"))

        assertEquals("4.5.9", info.version)
        assertEquals(Instant.ofEpochMilli(1_791_538_672_000), info.programStart)
        assertEquals(Instant.ofEpochMilli(1_791_540_232_000), info.lastDataUpdate)
    }

    @Test
    fun `a detailed host list yields every host with its check details`() {
        val hosts = StatusParser.hosts(Fixtures.json("hostlist_details.json"))

        assertEquals((1..8).map { "host%02d".format(it) }, hosts.map { it.name })
        assertTrue(hosts.all { it.state == HostState.UP && it.check.detailsAvailable })
        assertTrue(hosts.all { it.check.lastCheck != null && it.check.maxAttempts > 0 })
    }

    @Test
    fun `host problems carry acknowledgement, state type and attempts`() {
        val hosts = StatusParser.hosts(Fixtures.json("hostlist_problems_details.json"))

        assertEquals(3, hosts.size)
        hosts.forEach { host ->
            assertEquals(HostState.DOWN, host.state)
            assertEquals(StateType.HARD, host.check.stateType)
            assertTrue(host.check.acknowledged)
            assertEquals(host.check.maxAttempts, host.check.currentAttempt)
            assertFalse(host.check.inDowntime)
            assertEquals("CRITICAL - Host Unreachable (192.0.2.10)", host.check.pluginOutput)
        }
    }

    @Test
    fun `service problems are flattened from the host to service nesting`() {
        val services = StatusParser.services(Fixtures.json("servicelist_problems_details.json"))

        assertEquals(
            listOf("host12" to "PING", "host13" to "SSH", "host13" to "HTTP"),
            services.map {
                it.hostName to
                    it.description
            },
        )
        val first = services.first()
        assertEquals(ServiceState.WARNING, first.state)
        assertEquals(StateType.HARD, first.check.stateType)
        assertTrue(first.check.acknowledged)
        assertTrue(first.check.checksEnabled && first.check.notificationsEnabled)
        assertEquals(Instant.ofEpochMilli(1_791_540_077_000), first.check.lastCheck)
        assertEquals(3 to 3, first.check.currentAttempt to first.check.maxAttempts)
    }

    @Test
    fun `a list without details yields degraded records with name and state only`() {
        val services = StatusParser.services(Fixtures.json("servicelist_nodetails_single.json"))
        val hosts = StatusParser.hosts(Fixtures.json("hostlist_nodetails.json"))

        assertEquals(1, services.size)
        assertFalse(services.single().check.detailsAvailable)
        assertEquals(ServiceState.OK, services.single().state)
        assertEquals(8, hosts.size)
        assertTrue(hosts.all { !it.check.detailsAvailable && it.state == HostState.UP })
    }

    @Test
    fun `hosts with no matching services are emitted empty by Nagios and yield nothing`() {
        val body = Fixtures.json("servicelist_beyond_end.json")

        assertEquals(12, (body["data"] as JsonObject)["servicelist"].let { (it as JsonObject).size })
        assertTrue(StatusParser.services(body).isEmpty())
    }

    @Test
    fun `an unrecognised state is surfaced as a problem rather than treated as fine`() {
        val body = json(
            """{"data": {"servicelist": {"web01": {"Disk": {"status": "exploded"}}},
               "hostlist": {"web01": "sideways"}}}""",
        )

        assertEquals(ServiceState.UNKNOWN, StatusParser.services(body).single().state)
        assertEquals(HostState.UNREACHABLE, StatusParser.hosts(body).single().state)
    }

    @Test
    fun `missing and mistyped fields fall back to neutral defaults instead of failing the poll`() {
        val body = json(
            """{"data": {"servicelist": {"web01": {"Disk": {"status": "critical", "last_check": 0,
               "current_attempt": "many", "problem_has_been_acknowledged": "perhaps"}}}}}""",
        )

        val check = StatusParser.services(body).single().check

        assertNull("0 means never", check.lastCheck)
        assertEquals(0, check.currentAttempt)
        assertFalse(check.acknowledged)
        assertTrue(check.checksEnabled)
        assertEquals(StateType.HARD, check.stateType)
    }

    @Test
    fun `a soft state and scheduled downtime are read`() {
        val body = json(
            """{"data": {"servicelist": {"web01": {"Disk": {"status": "critical", "state_type": "soft",
               "current_attempt": 2, "max_attempts": 3, "scheduled_downtime_depth": 1,
               "checks_enabled": false, "notifications_enabled": false}}}}}""",
        )

        val check = StatusParser.services(body).single().check

        assertEquals(StateType.SOFT, check.stateType)
        assertTrue(check.inDowntime)
        assertFalse(check.checksEnabled)
        assertFalse(check.notificationsEnabled)
    }

    @Test
    fun `an empty or unexpected body yields nothing rather than throwing`() {
        assertTrue(StatusParser.hosts(json("{}")).isEmpty())
        assertTrue(StatusParser.services(json("""{"data": {"servicelist": []}}""")).isEmpty())
    }

    private fun json(text: String) = TEST_JSON.parseToJsonElement(text) as JsonObject
}
