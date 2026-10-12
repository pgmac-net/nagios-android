// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.CachedDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailUiStateTest {
    private val web01 = HostStatus("web01", HostState.UP, CheckStatus(pluginOutput = "PING OK"))
    private val db01 = HostStatus("db01", HostState.DOWN, CheckStatus())
    private val disk = ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(pluginOutput = "97% used"))
    private val snapshot = StatusSnapshot(
        hosts = listOf(web01, db01),
        serviceProblems = listOf(disk),
        fetchedAt = T0,
        serviceStates = listOf(
            ServiceStateEntry("web01", "Ping", ServiceState.OK),
            ServiceStateEntry("web01", "Disk /", ServiceState.CRITICAL),
            ServiceStateEntry("web01", "Load", ServiceState.WARNING),
            ServiceStateEntry("db01", "Ping", ServiceState.OK),
        ),
    )
    private val status =
        ProfileStatus(report = ProblemClassifier.classify(snapshot), snapshot = snapshot, lastSuccess = T0)
    private val later = T0.plusSeconds(600)

    @Test
    fun `how old a record may be before it is called stale comes from the profile, loaded or not`() {
        val hourly = status.copy(staleAfter = java.time.Duration.ofHours(2))

        assertEquals(
            java.time.Duration.ofHours(2),
            detailUiState(ObjectDetail(ObjectRef("web01")), hourly, later).staleAfter,
        )
        assertEquals(
            "with nothing polled yet it is still the profile's",
            java.time.Duration.ofHours(2),
            detailUiState(
                ObjectDetail(ObjectRef("web01")),
                ProfileStatus(staleAfter = java.time.Duration.ofHours(2)),
                later,
            ).staleAfter,
        )
        assertEquals(
            "and the shortest when the profile is not known",
            net.pgmac.nagwatch.status.Staleness.DEFAULT,
            detailUiState(ObjectDetail(ObjectRef("web01")), null, later).staleAfter,
        )
    }

    @Test
    fun `a host never opened before is shown from the last poll while it loads`() {
        val state = detailUiState(ObjectDetail(ObjectRef("web01")), status, later)

        val record = state.detail.record as CachedDetail.Host
        assertEquals(web01, record.status)
        assertEquals("and says how old that is", T0, record.fetchedAt)
        assertEquals("web01", state.title)
    }

    @Test
    fun `a service with a problem is shown from the last poll too`() {
        val state = detailUiState(ObjectDetail(ObjectRef("web01", "Disk /")), status, later)

        assertEquals(disk, (state.detail.record as CachedDetail.Service).status)
        assertEquals("web01 / Disk /", state.title)
        assertEquals("97% used", state.check?.pluginOutput)
    }

    @Test
    fun `a service that was fine has no details anywhere yet, only the state the poll gave it`() {
        val state = detailUiState(ObjectDetail(ObjectRef("web01", "Ping")), status, later)

        assertNull(state.detail.record)
        assertNull(state.check)
        assertEquals(ServiceState.OK, state.state)
    }

    @Test
    fun `details the poll fetched for a state the service has left are not used as a stand-in`() {
        val recovered = snapshot.copy(
            serviceStates = snapshot.serviceStates.map {
                if (it.description == "Disk /") it.copy(state = ServiceState.OK) else it
            },
        )
        val moved = status.copy(snapshot = recovered)

        val state = detailUiState(ObjectDetail(ObjectRef("web01", "Disk /")), moved, later)

        assertNull(state.detail.record)
        assertEquals(ServiceState.OK, state.state)
    }

    @Test
    fun `a fetched record always wins over the poll's`() {
        val fresh = CachedDetail.Service(disk.copy(state = ServiceState.OK), later)

        val state = detailUiState(ObjectDetail(ObjectRef("web01", "Disk /"), record = fresh), status, later)

        assertEquals(fresh, state.detail.record)
        assertEquals(ServiceState.OK, state.state)
    }

    @Test
    fun `a host lists its own services, worst first`() {
        val state = detailUiState(ObjectDetail(ObjectRef("web01")), status, later)

        assertEquals(listOf("Disk /", "Load", "Ping"), state.services.map { it.service })
        assertNull("a host has no host link", state.host)
    }

    @Test
    fun `a service knows its host and that host's state`() {
        val state = detailUiState(ObjectDetail(ObjectRef("db01", "Ping")), status, later)

        assertEquals("db01", state.host?.hostName)
        assertEquals(HostState.DOWN, state.host?.state)
        assertTrue(state.services.isEmpty())
    }

    @Test
    fun `with no poll in memory there is simply nothing extra to show`() {
        val state = detailUiState(ObjectDetail(ObjectRef("web01", "Ping")), null, later)

        assertNull(state.detail.record)
        assertNull(state.state)
        assertNull(state.host)
        assertEquals(later, state.now)
    }

    @Test
    fun `a length from now starts at the moment it is sent, to the second`() {
        val now = T0.plusMillis(750)

        assertEquals(T0 to T0.plusSeconds(1800), DowntimeWindow.FromNow(Duration.ofMinutes(30)).resolve(now))
    }

    @Test
    fun `a chosen window is taken as chosen, whatever the time is now`() {
        val window = DowntimeWindow.Between(T0.plusSeconds(3600), T0.plusSeconds(7200))

        assertEquals(T0.plusSeconds(3600) to T0.plusSeconds(7200), window.resolve(T0.plusSeconds(99)))
    }

    @Test
    fun `a window must end after it starts, and not be over already`() {
        assertTrue(DowntimeWindow.FromNow(Duration.ofMinutes(30)).isValid(T0))
        assertTrue(DowntimeWindow.Between(T0.minusSeconds(60), T0.plusSeconds(60)).isValid(T0))
        assertFalse("ends before it starts", DowntimeWindow.Between(T0.plusSeconds(60), T0.plusSeconds(30)).isValid(T0))
        assertFalse("no length", DowntimeWindow.Between(T0.plusSeconds(60), T0.plusSeconds(60)).isValid(T0))
        assertFalse("already over", DowntimeWindow.Between(T0.minusSeconds(120), T0.minusSeconds(60)).isValid(T0))
    }

    @Test
    fun `dates carry the year, in the reader's time zone`() {
        val instant = Instant.parse("2024-03-05T22:30:00Z")

        assertEquals("5 Mar 2024 22:30", formatWhen(instant, ZoneId.of("UTC")))
        assertEquals("6 Mar 2024 08:30", formatWhen(instant, ZoneId.of("Australia/Brisbane")))
        assertEquals("", formatWhen(null))
    }

    @Test
    fun `lengths of time read in their two most useful units`() {
        assertEquals("45m", formatSpan(Duration.ofMinutes(45)))
        assertEquals("2h 00m", formatSpan(Duration.ofHours(2)))
        assertEquals("2h 05m", formatSpan(Duration.ofMinutes(125)))
        assertEquals("3d 4h", formatSpan(Duration.ofHours(76)))
        assertEquals("", formatSpan(null))
        assertEquals("", formatSpan(Duration.ofMinutes(-5)))
    }
}
