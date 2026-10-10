// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.status.cache.StatusDatabase
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

    var serviceResult: (ObjectRef) -> NagiosResult<ServiceStatus> = { failure() }
    var hostResult: (String) -> NagiosResult<HostStatus> = { failure() }
    var commentsResult: (ObjectRef) -> NagiosResult<List<Comment>> = { NagiosResult.Success(emptyList()) }
    var downtimesResult: (ObjectRef) -> NagiosResult<List<Downtime>> = { NagiosResult.Success(emptyList()) }

    override suspend fun fetchService(ref: ObjectRef): NagiosResult<ServiceStatus> = serviceResult(ref)

    override suspend fun fetchHost(hostName: String): NagiosResult<HostStatus> = hostResult(hostName)

    override suspend fun fetchComments(ref: ObjectRef): NagiosResult<List<Comment>> = commentsResult(ref)

    override suspend fun fetchDowntimes(ref: ObjectRef): NagiosResult<List<Downtime>> = downtimesResult(ref)
}

/** The status cache on an in-memory database. Close the database in the test's teardown. */
fun inMemoryStatusDatabase(): StatusDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StatusDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** Remembers the chosen profile in memory, as the DataStore-backed one does on disk. */
class FakeSelectedProfile(initial: Long? = null) : SelectedProfile {
    private val state = MutableStateFlow(initial)
    override val id: Flow<Long?> = state

    override suspend fun select(profileId: Long) {
        state.value = profileId
    }
}

fun failure(error: NagiosError = NagiosError.Http(500)): NagiosResult.Failure = NagiosResult.Failure(error)

fun provider(client: FakeClient, onCreate: (ConnectionSettings) -> Unit = {}) = NagiosClientProvider { settings ->
    onCreate(settings)
    client
}

fun comment(
    id: Long,
    text: String = "note $id",
    at: Instant = T0,
    kind: Comment.Kind = Comment.Kind.USER,
    author: String = "alice",
) = Comment(id = id, kind = kind, author = author, text = text, enteredAt = at, persistent = true, expiresAt = null)

fun downtime(
    id: Long,
    comment: String = "maintenance $id",
    fixed: Boolean = true,
    inEffect: Boolean = false,
    author: String = "bob",
) = Downtime(
    id = id,
    author = author,
    comment = comment,
    start = T0,
    end = T0.plusSeconds(7200),
    fixed = fixed,
    duration = java.time.Duration.ofHours(2),
    inEffect = inEffect,
)

/** A profile as the editor would save it. */
suspend fun ProfileRepository.saveTestProfile(name: String = "Home"): Long = save(
    ProfileDraft(
        id = null,
        name = name,
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        password = SecretInput.Replace("pw"),
        accessClientId = "",
        accessClientSecret = SecretInput.Keep,
        customHeaders = emptyList(),
        allowCleartext = false,
    ),
)
