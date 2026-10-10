// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseModelTest {
    @Test
    fun `hosts are listed worst first, then by name ignoring case`() {
        val rows = hostRows(
            host("web02"),
            host("Db01"),
            host("new", HostState.PENDING),
            host("far", HostState.UNREACHABLE),
            host("zed", HostState.DOWN),
            host("app01", HostState.DOWN),
        )

        assertEquals(listOf("app01", "zed", "far", "new", "Db01", "web02"), rows.map { it.title })
    }

    @Test
    fun `services are listed critical, warning, unknown, then the rest`() {
        val rows = serviceRows(
            states = listOf(
                state("a", "ok", ServiceState.OK),
                state("a", "pending", ServiceState.PENDING),
                state("a", "unknown", ServiceState.UNKNOWN),
                state("a", "warning", ServiceState.WARNING),
                state("a", "critical", ServiceState.CRITICAL),
            ),
            problems = listOf(
                service("a", "unknown", ServiceState.UNKNOWN),
                service("a", "warning", ServiceState.WARNING),
                service("a", "critical", ServiceState.CRITICAL),
            ),
        )

        assertEquals(listOf("critical", "warning", "unknown", "pending", "ok"), rows.map { it.service })
    }

    @Test
    fun `within a severity the handled come last, and still outrank the next severity`() {
        val acked = CheckStatus(acknowledged = true)
        val rows = serviceRows(
            states = listOf(
                state("a", "warn", ServiceState.WARNING),
                state("a", "crit acked", ServiceState.CRITICAL),
                state("b", "crit", ServiceState.CRITICAL),
                state("a", "warn in downtime", ServiceState.WARNING),
                state("z", "crit", ServiceState.CRITICAL),
            ),
            problems = listOf(
                service("a", "warn", ServiceState.WARNING),
                service("a", "crit acked", ServiceState.CRITICAL, acked),
                service("b", "crit", ServiceState.CRITICAL),
                service("a", "warn in downtime", ServiceState.WARNING, CheckStatus(downtimeDepth = 1)),
                service("z", "crit", ServiceState.CRITICAL),
            ),
        )

        assertEquals(
            listOf("b / crit", "z / crit", "a / crit acked", "a / warn", "a / warn in downtime"),
            rows.map { it.title },
        )
    }

    @Test
    fun `a problem whose details are missing is unhandled as far as anyone knows, so it sorts with them`() {
        // The cheap list says critical; the detailed list has not caught up.
        val rows = serviceRows(
            states = listOf(
                state("a", "no details", ServiceState.CRITICAL),
                state("a", "acked", ServiceState.CRITICAL),
            ),
            problems = listOf(service("a", "acked", ServiceState.CRITICAL, CheckStatus(acknowledged = true))),
        )

        assertEquals(listOf("no details", "acked"), rows.map { it.service })
    }

    @Test
    fun `a host that is up has nothing to handle, and one that is down says how it is handled`() {
        val rows = hostRows(
            host("up"),
            host("down", HostState.DOWN),
            host("acked", HostState.DOWN, CheckStatus(acknowledged = true)),
        ).associateBy { it.title }

        assertNull(rows.getValue("up").handling)
        assertFalse(rows.getValue("up").isHandled)
        assertEquals(Handling.UNHANDLED, rows.getValue("down").handling)
        assertEquals(Handling.ACKNOWLEDGED, rows.getValue("acked").handling)
        assertTrue(rows.getValue("acked").isHandled)
    }

    @Test
    fun `a host with checks switched off says so even while it is up`() {
        val row = hostRows(host("quiet", check = CheckStatus(checksEnabled = false))).single()

        assertEquals(setOf(Marker.CHECKS_DISABLED), row.markers)
    }

    @Test
    fun `every service is listed, and only those with a problem have details`() {
        val rows = serviceRows(
            states = listOf(state("web01", "Ping", ServiceState.OK), state("web01", "Disk", ServiceState.CRITICAL)),
            problems = listOf(service("web01", "Disk", ServiceState.CRITICAL, CheckStatus(pluginOutput = "97% used"))),
        )

        assertEquals(listOf("web01 / Disk", "web01 / Ping"), rows.map { it.title })
        assertEquals("97% used", rows[0].check?.pluginOutput)
        assertEquals(Handling.UNHANDLED, rows[0].handling)
        assertNull("an OK service was fetched by name and state only", rows[1].check)
        assertNull(rows[1].handling)
    }

    @Test
    fun `a service on a down host is found too, and counts as handled by the host`() {
        val rows = serviceRows(
            hosts = listOf(host("web01", HostState.DOWN)),
            states = listOf(state("web01", "Disk", ServiceState.CRITICAL)),
            problems = listOf(service("web01", "Disk", ServiceState.CRITICAL)),
        )

        assertEquals(Handling.HOST_DOWN, rows.single().handling)
        assertTrue(rows.single().isHandled)
    }

    @Test
    fun `details fetched for a state the service has since left are not shown against the new state`() {
        // The two lists are separate requests; the service recovered between them.
        val rows = serviceRows(
            states = listOf(state("web01", "Disk", ServiceState.OK)),
            problems = listOf(service("web01", "Disk", ServiceState.CRITICAL, CheckStatus(pluginOutput = "97% used"))),
        )

        assertEquals(ServiceState.OK, rows.single().state)
        assertNull(rows.single().check)
        assertNull(rows.single().handling)
    }

    @Test
    fun `two services with the same name on different hosts are different rows`() {
        val rows = serviceRows(
            states = listOf(state("a", "Ping", ServiceState.OK), state("b", "Ping", ServiceState.OK)),
        )

        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `search matches part of a name, in any case, and ignores stray spaces`() {
        val view = BrowseFilter<HostState>(query = "  WEB ").apply(hostRows(host("web01"), host("db01"), host("MyWeb")))

        assertEquals(listOf("MyWeb", "web01"), view.rows.map { it.title })
    }

    @Test
    fun `searching services matches the host name as well as the check name`() {
        val rows = serviceRows(
            states = listOf(
                state("web01", "Ping", ServiceState.OK),
                state("db01", "Ping", ServiceState.OK),
                state("db01", "Web cert", ServiceState.OK),
            ),
        )

        val view = BrowseFilter<ServiceState>(query = "web").apply(rows)

        assertEquals(listOf("db01 / Web cert", "web01 / Ping"), view.rows.map { it.title })
    }

    @Test
    fun `a search with characters that mean something elsewhere is taken literally`() {
        val rows = serviceRows(states = listOf(state("web01", "Disk /var (50%)", ServiceState.OK)))

        assertEquals(1, BrowseFilter<ServiceState>(query = "/var (50%)").apply(rows).rows.size)
        assertEquals(0, BrowseFilter<ServiceState>(query = ".*").apply(rows).rows.size)
    }

    @Test
    fun `state chips narrow the list, and several can be on at once`() {
        val rows = hostRows(host("a"), host("b", HostState.DOWN), host("c", HostState.UNREACHABLE))

        val view = BrowseFilter(states = setOf(HostState.DOWN, HostState.UNREACHABLE)).apply(rows)

        assertEquals(listOf("b", "c"), view.rows.map { it.title })
    }

    @Test
    fun `chip counts follow the search but not the chips, so a chip always says what it would show`() {
        val rows = hostRows(host("web01"), host("web02", HostState.DOWN), host("db01", HostState.DOWN))

        val view = BrowseFilter(query = "web", states = setOf(HostState.UP)).apply(rows)

        assertEquals(mapOf(HostState.UP to 1, HostState.DOWN to 1), view.counts)
        assertEquals(listOf("web01"), view.rows.map { it.title })
    }

    @Test
    fun `handled problems are shown until switched off, and are still counted when hidden`() {
        val rows = hostRows(
            host("down", HostState.DOWN),
            host("acked", HostState.DOWN, CheckStatus(acknowledged = true)),
            host("up"),
        )

        val shown = BrowseFilter<HostState>().apply(rows)
        val hidden = BrowseFilter<HostState>(showHandled = false).apply(rows)

        assertEquals(listOf("down", "acked", "up"), shown.rows.map { it.title })
        assertEquals(listOf("down", "up"), hidden.rows.map { it.title })
        assertEquals(1, hidden.handledCount)
        assertEquals("the hidden one is not in the chip count", 1, hidden.counts[HostState.DOWN])
    }

    @Test
    fun `hiding handled never hides something that is not a problem`() {
        val rows = hostRows(host("up"), host("pending", HostState.PENDING))

        assertEquals(2, BrowseFilter<HostState>(showHandled = false).apply(rows).rows.size)
    }

    private fun hostRows(vararg hosts: HostStatus): List<BrowseRow<HostState>> {
        val snapshot = StatusSnapshot(hosts.toList(), emptyList(), T0)
        return BrowseRows.hosts(snapshot, ProblemClassifier.classify(snapshot))
    }

    private fun serviceRows(
        hosts: List<HostStatus> = listOf(host("web01"), host("db01"), host("a"), host("b")),
        states: List<ServiceStateEntry>,
        problems: List<ServiceStatus> = emptyList(),
    ): List<BrowseRow<ServiceState>> {
        val snapshot = StatusSnapshot(hosts, problems, T0, states)
        return BrowseRows.services(snapshot, ProblemClassifier.classify(snapshot))
    }

    private fun host(name: String, state: HostState = HostState.UP, check: CheckStatus = CheckStatus()) =
        HostStatus(name, state, check)

    private fun state(host: String, name: String, state: ServiceState) = ServiceStateEntry(host, name, state)

    private fun service(host: String, name: String, state: ServiceState, check: CheckStatus = CheckStatus()) =
        ServiceStatus(host, name, state, check)
}
