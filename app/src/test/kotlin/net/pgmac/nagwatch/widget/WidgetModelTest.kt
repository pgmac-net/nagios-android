// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import java.time.Duration
import java.time.Instant
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.cache.PollAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a widget shows, decided from what is on disk. Old green is never shown as current. */
class WidgetModelTest {
    private val home = profile(PollInterval.FIFTEEN_MINUTES)
    private val worked = PollAttempt(T0, failure = null)

    // ---------------------------------------------------------------- the five states

    @Test
    fun `a widget whose profile was deleted says so and shows nothing else`() {
        val content = WidgetModel.of(null, snapshot(critical("Disk /")), worked, T0)

        assertEquals(WidgetContent(WidgetState.PROFILE_GONE), content)
    }

    @Test
    fun `a profile that has never been polled has no data, which is not the same as nothing wrong`() {
        val content = WidgetModel.of(home, snapshot = null, attempt = null, now = T0)

        assertEquals(WidgetState.NO_DATA, content.state)
        assertEquals("Home", content.profileName)
        assertNull("no counts, not zeroes", content.counts)
        assertNull(content.asOf)
    }

    @Test
    fun `a profile whose only poll failed is unreachable, with the reason and still no counts`() {
        val failed = PollAttempt(T0, OutageCause.CREDENTIALS_REJECTED)

        val content = WidgetModel.of(home, snapshot = null, attempt = failed, now = T0)

        assertEquals(WidgetState.UNREACHABLE, content.state)
        assertEquals(OutageCause.CREDENTIALS_REJECTED, content.cause)
        assertNull(content.counts)
    }

    @Test
    fun `a recent good poll is current`() {
        val content = WidgetModel.of(home, snapshot(critical("Disk /")), worked, T0 + Duration.ofMinutes(10))

        assertEquals(WidgetState.CURRENT, content.state)
        assertEquals(T0, content.asOf)
        assertNull(content.cause)
        assertFalse(content.isDemoted)
    }

    @Test
    fun `it is stale past twice the profile's interval, and not at exactly twice`() {
        PollInterval.entries.forEach { interval ->
            val limit = Duration.ofMinutes(2L * interval.minutes)
            val profile = profile(interval)

            assertEquals("$interval at the limit", WidgetState.CURRENT, stateAt(profile, T0 + limit))
            assertEquals("$interval past it", WidgetState.STALE, stateAt(profile, T0 + limit + Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `an hourly profile is not called stale at ninety minutes`() {
        // Under a fixed half hour it would have been stale for half of every hour.
        assertEquals(WidgetState.CURRENT, stateAt(profile(PollInterval.ONE_HOUR), T0 + Duration.ofMinutes(90)))
    }

    @Test
    fun `a failed poll after a good one is unreachable, and keeps what the good one found`() {
        val failed = PollAttempt(T0 + Duration.ofMinutes(15), OutageCause.UNREACHABLE)

        val content = WidgetModel.of(home, snapshot(critical("Disk /")), failed, T0 + Duration.ofMinutes(16))

        assertEquals(WidgetState.UNREACHABLE, content.state)
        assertEquals(OutageCause.UNREACHABLE, content.cause)
        assertEquals("the last numbers stay", 1, content.counts?.critical)
        assertEquals("and say how old they are", T0, content.asOf)
        assertTrue(content.isDemoted)
    }

    @Test
    fun `a good poll after a failed one is current again`() {
        val content = WidgetModel.of(home, snapshot(critical("Disk /")), worked, T0 + Duration.ofMinutes(1))

        assertEquals(WidgetState.CURRENT, content.state)
        assertNull(content.cause)
    }

    @Test
    fun `old and failing is unreachable, which says more than stale`() {
        val failed = PollAttempt(T0 + Duration.ofHours(5), OutageCause.SERVER_ERROR)

        val content = WidgetModel.of(home, snapshot(critical("Disk /")), failed, T0 + Duration.ofHours(6))

        assertEquals(WidgetState.UNREACHABLE, content.state)
    }

    @Test
    fun `stale is demoted, and has no cause because nothing is known to have failed`() {
        val content = WidgetModel.of(home, snapshot(critical("Disk /")), worked, T0 + Duration.ofHours(3))

        assertEquals(WidgetState.STALE, content.state)
        assertTrue(content.isDemoted)
        assertNull(content.cause)
        assertEquals(1, content.counts?.critical)
    }

    @Test
    fun `a cache with no record of any attempt is judged on its age alone`() {
        // A poll from before attempts were recorded.
        assertEquals(WidgetState.CURRENT, WidgetModel.of(home, snapshot(), attempt = null, now = T0).state)
        assertEquals(
            WidgetState.STALE,
            WidgetModel.of(home, snapshot(), attempt = null, now = T0 + Duration.ofHours(3)).state,
        )
    }

    // ---------------------------------------------------------------- counts and rows

    @Test
    fun `nothing wrong is zeroes, no rows and no worst state`() {
        val content = WidgetModel.of(home, snapshot(), worked, T0)

        assertEquals(ProblemCounts(hostsDown = 0, critical = 0, warning = 0, unknown = 0), content.counts)
        assertTrue(content.rows.isEmpty())
        assertNull(content.worst)
        assertEquals(0, content.more)
    }

    @Test
    fun `rows are the unhandled problems, worst first, as the Problems tab has them`() {
        val poll = snapshot(
            service("web01", "Odd", ServiceState.UNKNOWN),
            service("web01", "Load", ServiceState.WARNING),
            critical("Disk /"),
            hosts = listOf(up("web01"), HostStatus("db01", HostState.DOWN, CheckStatus(pluginOutput = "no reply"))),
        )

        val content = WidgetModel.of(home, poll, worked, T0)

        assertEquals(
            listOf(Severity.HOST_DOWN, Severity.CRITICAL, Severity.WARNING, Severity.UNKNOWN),
            content.rows.map { it.severity },
        )
        assertEquals(ObjectRef("db01"), content.rows.first().ref)
        assertEquals(ObjectRef("web01", "Disk /"), content.rows[1].ref)
        assertEquals(Severity.HOST_DOWN, content.worst)
        assertEquals(ProblemCounts(hostsDown = 1, critical = 1, warning = 1, unknown = 1), content.counts)
    }

    @Test
    fun `handled problems are counted apart and are not rows`() {
        val poll = snapshot(
            critical("Disk /"),
            service("web01", "Load", ServiceState.WARNING, CheckStatus(acknowledged = true)),
            service("web01", "HTTP", ServiceState.CRITICAL, CheckStatus(downtimeDepth = 1)),
        )

        val content = WidgetModel.of(home, poll, worked, T0)

        assertEquals(listOf(ObjectRef("web01", "Disk /")), content.rows.map { it.ref })
        assertEquals(2, content.handled)
        assertEquals(1, content.counts?.critical)
    }

    @Test
    fun `services under a host that is down are not rows of their own`() {
        val poll = snapshot(
            service("db01", "Replication", ServiceState.CRITICAL),
            hosts = listOf(HostStatus("db01", HostState.DOWN, CheckStatus())),
        )

        val content = WidgetModel.of(home, poll, worked, T0)

        assertEquals(listOf(ObjectRef("db01")), content.rows.map { it.ref })
    }

    @Test
    fun `a row has one line of output, when it began, and whether Nagios is still retrying`() {
        val since = T0 - Duration.ofHours(2)
        val poll = snapshot(
            service(
                "web01",
                "Disk /",
                ServiceState.CRITICAL,
                CheckStatus(
                    pluginOutput = "  DISK CRITICAL - 97%\nsecond line",
                    lastStateChange = since,
                    stateType = StateType.SOFT,
                ),
            ),
            service("web01", "Load", ServiceState.WARNING, CheckStatus(pluginOutput = "x".repeat(5_000))),
        )

        val rows = WidgetModel.of(home, poll, worked, T0).rows

        assertEquals("DISK CRITICAL - 97%", rows[0].output)
        assertEquals(since, rows[0].since)
        assertTrue(rows[0].soft)
        assertFalse(rows[1].soft)
        assertEquals(300, rows[1].output.length)
        assertNull(rows[1].since)
    }

    @Test
    fun `more problems than a widget could ever show are counted, not all handed over`() {
        val many = Array(WidgetModel.MAX_ROWS + 7) { service("web01", "check $it", ServiceState.WARNING) }

        val content = WidgetModel.of(home, snapshot(*many), worked, T0)

        assertEquals(WidgetModel.MAX_ROWS, content.rows.size)
        assertEquals(7, content.more)
        assertEquals("the count is still the whole truth", WidgetModel.MAX_ROWS + 7, content.counts?.warning)
    }

    @Test
    fun `exactly as many as fit leaves nothing over`() {
        val many = Array(WidgetModel.MAX_ROWS) { service("web01", "check $it", ServiceState.WARNING) }

        assertEquals(0, WidgetModel.of(home, snapshot(*many), worked, T0).more)
    }

    // ----------------------------------------------------------------

    private fun stateAt(profile: Profile, now: Instant) = WidgetModel.of(profile, snapshot(), worked, now).state

    private fun snapshot(vararg services: ServiceStatus, hosts: List<HostStatus> = listOf(up("web01"))) =
        StatusSnapshot(hosts, services.toList(), T0)

    private fun up(name: String) = HostStatus(name, HostState.UP, CheckStatus())

    private fun critical(name: String) = service("web01", name, ServiceState.CRITICAL)

    private fun service(host: String, name: String, state: ServiceState, check: CheckStatus = CheckStatus()) =
        ServiceStatus(host, name, state, check)

    private fun profile(interval: PollInterval) = Profile(
        id = 1,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        hasPassword = true,
        accessClientId = "",
        hasAccessClientSecret = false,
        customHeaderNames = emptyList(),
        allowCleartext = false,
        notifications = NotificationSettings(interval = interval),
    )

    private companion object {
        val T0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    }
}
