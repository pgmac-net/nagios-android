// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Instant
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "unhandled problem" decision table from CONTEXT.md, one rule per test. */
class ProblemClassifierTest {
    private val hard = CheckStatus(stateType = StateType.HARD, lastStateChange = at(100))
    private val up = HostStatus("web01", HostState.UP, hard)

    @Test
    fun `a plain service problem on an up host is unhandled`() {
        val report = classify(hosts = listOf(up), services = listOf(service(ServiceState.CRITICAL)))

        assertEquals(listOf(Handling.UNHANDLED), report.unhandled.map { it.handling })
        assertEquals(ProblemCounts(hostsDown = 0, critical = 1, warning = 0, unknown = 0), report.counts)
        assertTrue(report.handled.isEmpty())
    }

    @Test
    fun `an acknowledged problem is handled`() {
        val report = classify(listOf(up), listOf(service(ServiceState.CRITICAL, hard.copy(acknowledged = true))))

        assertEquals(listOf(Handling.ACKNOWLEDGED), report.handled.map { it.handling })
        assertEquals(0, report.counts.total)
    }

    @Test
    fun `a problem in its own downtime is handled`() {
        val report = classify(listOf(up), listOf(service(ServiceState.WARNING, hard.copy(downtimeDepth = 1))))

        assertEquals(listOf(Handling.IN_DOWNTIME), report.handled.map { it.handling })
    }

    @Test
    fun `a service is handled when its host is in downtime, even though its own depth is zero`() {
        val hostInDowntime = up.copy(check = hard.copy(downtimeDepth = 2))

        val report = classify(listOf(hostInDowntime), listOf(service(ServiceState.CRITICAL)))

        assertEquals(listOf(Handling.IN_DOWNTIME), report.handled.map { it.handling })
        assertEquals(0, report.counts.total)
    }

    @Test
    fun `services on a down host roll up under the host and are not counted`() {
        val down = HostStatus("web01", HostState.DOWN, hard)

        val report =
            classify(listOf(down), listOf(service(ServiceState.CRITICAL), service(ServiceState.WARNING, name = "Load")))

        val host = report.unhandled.single() as HostProblem
        assertEquals(listOf(Handling.HOST_DOWN, Handling.HOST_DOWN), host.services.map { it.handling })
        assertEquals(listOf(ServiceState.CRITICAL, ServiceState.WARNING), host.services.map { it.service.state })
        assertEquals(ProblemCounts(hostsDown = 1, critical = 0, warning = 0, unknown = 0), report.counts)
    }

    @Test
    fun `an unreachable host also rolls up its services`() {
        val unreachable = HostStatus("web01", HostState.UNREACHABLE, hard)

        val report = classify(listOf(unreachable), listOf(service(ServiceState.UNKNOWN)))

        assertEquals(1, (report.unhandled.single() as HostProblem).services.size)
    }

    @Test
    fun `an acknowledged down host is handled and keeps its rolled-up services with it`() {
        val down = HostStatus("web01", HostState.DOWN, hard.copy(acknowledged = true))

        val report = classify(listOf(down), listOf(service(ServiceState.CRITICAL)))

        assertTrue(report.unhandled.isEmpty())
        assertEquals(1, (report.handled.single() as HostProblem).services.size)
        assertEquals(0, report.counts.total)
    }

    @Test
    fun `a soft problem still counts and is marked`() {
        val soft = hard.copy(stateType = StateType.SOFT, currentAttempt = 2, maxAttempts = 3)

        val report = classify(listOf(up), listOf(service(ServiceState.CRITICAL, soft)))

        assertEquals(setOf(Marker.SOFT), report.unhandled.single().markers)
        assertEquals(1, report.counts.critical)
    }

    @Test
    fun `disabled checks and disabled notifications still count and are marked`() {
        val silenced = hard.copy(checksEnabled = false, notificationsEnabled = false)

        val report = classify(listOf(up), listOf(service(ServiceState.WARNING, silenced)))

        assertEquals(setOf(Marker.CHECKS_DISABLED, Marker.NOTIFICATIONS_DISABLED), report.unhandled.single().markers)
        assertEquals(1, report.counts.warning)
    }

    @Test
    fun `a degraded record counts as unhandled because nothing proves otherwise`() {
        val degraded = CheckStatus(detailsAvailable = false)

        val report = classify(listOf(up), listOf(service(ServiceState.CRITICAL, degraded)))

        val problem = report.unhandled.single()
        assertEquals(Handling.UNHANDLED, problem.handling)
        assertEquals(setOf(Marker.DETAILS_UNAVAILABLE), problem.markers)
        assertEquals(1, report.degradedCount)
    }

    @Test
    fun `pending and ok are not problems`() {
        val report = classify(
            hosts = listOf(up, HostStatus("new01", HostState.PENDING, hard)),
            services = listOf(service(ServiceState.OK), service(ServiceState.PENDING, name = "New")),
        )

        assertTrue(report.unhandled.isEmpty())
        assertTrue(report.handled.isEmpty())
    }

    @Test
    fun `a service whose host is missing from the host list is judged on its own`() {
        val report = classify(hosts = emptyList(), services = listOf(service(ServiceState.UNKNOWN)))

        assertEquals(Handling.UNHANDLED, report.unhandled.single().handling)
        assertEquals(1, report.counts.unknown)
    }

    @Test
    fun `problems are ordered by severity, then confirmed before soft, then newest first`() {
        val soft = hard.copy(stateType = StateType.SOFT)
        val report = classify(
            hosts = listOf(
                up,
                HostStatus("db01", HostState.DOWN, hard),
                HostStatus("gw01", HostState.UNREACHABLE, hard),
            ),
            services = listOf(
                service(ServiceState.UNKNOWN, name = "unknown"),
                service(ServiceState.WARNING, name = "warning"),
                service(ServiceState.CRITICAL, soft.copy(lastStateChange = at(900)), name = "critical-soft"),
                service(ServiceState.CRITICAL, hard.copy(lastStateChange = at(100)), name = "critical-old"),
                service(ServiceState.CRITICAL, hard.copy(lastStateChange = at(500)), name = "critical-new"),
            ),
        )

        assertEquals(
            listOf("db01", "gw01", "critical-new", "critical-old", "critical-soft", "warning", "unknown"),
            report.unhandled.map(::label),
        )
    }

    private fun classify(hosts: List<HostStatus>, services: List<ServiceStatus>) =
        ProblemClassifier.classify(StatusSnapshot(hosts, services, FIXED_NOW))

    private fun service(state: ServiceState, check: CheckStatus = hard, name: String = "Disk /") =
        ServiceStatus(hostName = "web01", description = name, state = state, check = check)

    private fun label(problem: Problem): String = when (problem) {
        is HostProblem -> problem.host.name
        is ServiceProblem -> problem.service.description
    }

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
