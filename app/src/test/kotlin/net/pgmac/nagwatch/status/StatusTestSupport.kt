// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A clock tests can move. */
class MutableClock(private var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(by: java.time.Duration) {
        now += by
    }
}

val T0: Instant = Instant.parse("2026-10-09T10:00:00Z")
val CGI: HttpUrl = "https://nagios.example.org/nagios/cgi-bin/".toHttpUrl()

fun snapshot(
    at: Instant = T0,
    hosts: List<HostStatus> = listOf(HostStatus("web01", HostState.UP, CheckStatus())),
    services: List<ServiceStatus> = emptyList(),
) = StatusSnapshot(hosts, services, at)

fun criticalService(host: String = "web01", name: String = "Disk /", since: Instant = T0) = ServiceStatus(
    hostName = host,
    description = name,
    state = ServiceState.CRITICAL,
    check = CheckStatus(pluginOutput = "CRITICAL - value 97", lastStateChange = since),
)

/** Scripted stand-in for a Nagios: what connect and fetch return, and how often each was called. */
class FakeClient(
    var connectResult: NagiosResult<NagiosClient.Connection> = NagiosResult.Success(
        NagiosClient.Connection(CGI, ServerInfo("4.5.9", null, null)),
    ),
    var statusResult: () -> NagiosResult<StatusSnapshot> = { NagiosResult.Success(snapshot()) },
) : NagiosClient {
    var connects = 0
    var fetches = 0

    override suspend fun connect(): NagiosResult<NagiosClient.Connection> {
        connects++
        return connectResult
    }

    override suspend fun fetchStatus(): NagiosResult<StatusSnapshot> {
        fetches++
        return statusResult()
    }
}

fun failure(error: NagiosError = NagiosError.Http(500)): NagiosResult.Failure = NagiosResult.Failure(error)

fun provider(client: FakeClient, onCreate: (ConnectionSettings) -> Unit = {}) = NagiosClientProvider { settings ->
    onCreate(settings)
    client
}
