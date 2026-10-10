// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import java.time.Duration
import java.time.Instant
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.criticalService
import net.pgmac.nagwatch.status.snapshot
import net.pgmac.nagwatch.ui.home.HomeUiState
import net.pgmac.nagwatch.ui.home.selectProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProblemsModelTest {
    @Test
    fun `ages read in the two most useful units`() {
        fun age(d: Duration) = formatAge(T0 - d, T0)

        assertEquals("<1m", age(Duration.ofSeconds(20)))
        assertEquals("1m", age(Duration.ofSeconds(60)))
        assertEquals("42m", age(Duration.ofMinutes(42)))
        assertEquals("1h 00m", age(Duration.ofMinutes(60)))
        assertEquals("2h 04m", age(Duration.ofMinutes(124)))
        assertEquals("23h 59m", age(Duration.ofMinutes(23 * 60 + 59)))
        assertEquals("1d 0h", age(Duration.ofHours(24)))
        assertEquals("3d 4h", age(Duration.ofHours(3 * 24 + 4)))
    }

    @Test
    fun `an unknown time is blank and a time in the future is not negative`() {
        assertEquals("", formatAge(null, T0))
        assertEquals("<1m", formatAge(T0 + Duration.ofMinutes(5), T0))
    }

    @Test
    fun `the requested profile is shown, otherwise the first, otherwise none`() {
        val a = profile(1, "a")
        val b = profile(2, "b")

        assertEquals(b, selectProfile(listOf(a, b), 2))
        assertEquals("a deleted profile falls back", a, selectProfile(listOf(a, b), 99))
        assertEquals("nothing chosen yet", a, selectProfile(listOf(a, b), null))
        assertNull(selectProfile(emptyList(), 1))
    }

    @Test
    fun `a filter shows only the chosen kinds, and none chosen shows everything`() {
        val state = stateWith(
            hosts = listOf(HostStatus("db01", HostState.DOWN, check())),
            services = listOf(
                criticalService(name = "crit"),
                service(ServiceState.WARNING, "warn"),
                service(ServiceState.UNKNOWN, "unkn"),
            ),
        )

        assertEquals(listOf("db01", "web01 / crit", "web01 / warn", "web01 / unkn"), state.titles())
        assertEquals(listOf("db01"), state.copy(filter = setOf(ProblemKind.HOSTS)).titles())
        assertEquals(listOf("web01 / crit"), state.copy(filter = setOf(ProblemKind.CRITICAL)).titles())
        assertEquals(
            listOf("web01 / warn", "web01 / unkn"),
            state.copy(filter = setOf(ProblemKind.WARNING, ProblemKind.UNKNOWN)).titles(),
        )
    }

    @Test
    fun `an unreachable host is a host problem for the host filter`() {
        val state = stateWith(hosts = listOf(HostStatus("gw01", HostState.UNREACHABLE, check())))

        assertEquals(listOf("gw01"), state.copy(filter = setOf(ProblemKind.HOSTS)).titles())
    }

    @Test
    fun `titles come from the host or service, and output is the first line only`() {
        val report = ProblemClassifier.classify(
            snapshot(
                hosts = listOf(HostStatus("db01", HostState.DOWN, check("HOST DOWN" + NEWLINE + "second line"))),
                services = listOf(criticalService(host = "web01", name = "Disk /")),
            ),
        )

        val host = report.unhandled.filterIsInstance<HostProblem>().single()
        assertEquals("db01", host.title())
        assertEquals("HOST DOWN", host.output())
        val service = report.unhandled.first { it !is HostProblem }
        assertEquals("web01 / Disk /", service.title())
        assertEquals("CRITICAL - value 97", service.output())
    }

    @Test
    fun `a degraded record has no output to show`() {
        val state = stateWith(
            services = listOf(
                ServiceStatus("web01", "Odd", ServiceState.CRITICAL, CheckStatus(detailsAvailable = false)),
            ),
        )

        assertEquals("", state.visibleUnhandled.single().output())
    }

    private fun check(output: String = "") = CheckStatus(pluginOutput = output, lastStateChange = T0)

    private fun service(state: ServiceState, name: String) =
        ServiceStatus("web01", name, state, CheckStatus(lastStateChange = T0 - Duration.ofMinutes(1)))

    private fun stateWith(
        hosts: List<HostStatus> = emptyList(),
        services: List<ServiceStatus> = emptyList(),
    ): HomeUiState {
        val all = listOf(HostStatus("web01", HostState.UP, check())) + hosts
        val report = ProblemClassifier.classify(snapshot(hosts = all, services = services))
        return HomeUiState(
            loading = false,
            profiles = listOf(profile(1, "Home")),
            selected = profile(1, "Home"),
            status = ProfileStatus(report = report, lastSuccess = T0),
            now = Instant.parse("2026-10-09T10:00:00Z"),
        )
    }

    private fun HomeUiState.titles() = visibleUnhandled.map { it.title() }

    private fun profile(id: Long, name: String) = Profile(
        id = id,
        name = name,
        baseUrl = "https://nagios.example.org",
        username = "u",
        hasPassword = true,
        accessClientId = "",
        hasAccessClientSecret = false,
        customHeaderNames = emptyList(),
        allowCleartext = false,
    )
}

private const val NEWLINE = "\n"
