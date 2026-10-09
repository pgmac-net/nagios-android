// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.http.AnnotationParser
import net.pgmac.nagwatch.nagios.http.ConnectionInterceptor
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.http.ResilientListFetcher
import net.pgmac.nagwatch.nagios.http.StatusParser
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** A connection to one Nagios instance. Obtain one from [NagiosClientFactory]. */
interface NagiosClient {
    /**
     * Checks the instance answers as Nagios and finds its CGI directory.
     * [Connection.cgiBase] can be stored and passed back in [ConnectionSettings]
     * to skip the search next time.
     */
    suspend fun connect(): NagiosResult<Connection>

    /** Every host with details, every service's state, and details of services in a problem state. */
    suspend fun fetchStatus(): NagiosResult<StatusSnapshot>

    /** One service's full record, as fresh as Nagios has it. */
    suspend fun fetchService(ref: ObjectRef): NagiosResult<ServiceStatus>

    suspend fun fetchHost(hostName: String): NagiosResult<HostStatus>

    /** The object's own comments, newest first, at most [MAX_ANNOTATIONS]. */
    suspend fun fetchComments(ref: ObjectRef): NagiosResult<List<Comment>>

    /** The object's own scheduled downtimes, soonest first, at most [MAX_ANNOTATIONS]. */
    suspend fun fetchDowntimes(ref: ObjectRef): NagiosResult<List<Downtime>>

    data class Connection(val cgiBase: HttpUrl, val server: ServerInfo)

    companion object {
        /** Comments pile up for years on a busy object; this is a ceiling on what is held. */
        const val MAX_ANNOTATIONS = 200
    }
}

@Singleton
class NagiosClientFactory internal constructor(
    private val baseClient: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    @Inject
    constructor(baseClient: OkHttpClient, json: Json) : this(baseClient, json, Dispatchers.IO, Clock.systemUTC())

    fun create(settings: ConnectionSettings): NagiosClient {
        val client = baseClient.newBuilder()
            // Never follow redirects: credentials go only to the configured origin, and a
            // Cloudflare Access login redirect must be seen, not followed into an HTML page.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ConnectionInterceptor(settings))
            .build()
        return OkHttpNagiosClient(settings, NagiosApi(client, json, io), clock)
    }
}

internal class OkHttpNagiosClient(
    private val settings: ConnectionSettings,
    private val api: NagiosApi,
    private val clock: Clock,
) : NagiosClient {
    private val fetcher = ResilientListFetcher(api)
    private var cgiBase: HttpUrl? = settings.cgiBase

    override suspend fun connect(): NagiosResult<NagiosClient.Connection> {
        val violation = settings.policyViolation()
        var result: NagiosResult<NagiosClient.Connection> = NagiosResult.Failure(violation ?: NagiosError.NotNagios)
        if (violation == null) {
            for (candidate in cgiCandidates()) {
                result = probe(candidate)
                // Only "nothing Nagios-like here" justifies trying the next path. Anything
                // else (bad password, unreachable, Access) would fail the same way again.
                if ((result as? NagiosResult.Failure)?.error != NagiosError.NotNagios) break
            }
        }
        return result
    }

    private suspend fun probe(candidate: HttpUrl): NagiosResult<NagiosClient.Connection> =
        api.query(candidate, STATUS_CGI, listOf("query" to "programstatus")).map { body ->
            cgiBase = candidate
            NagiosClient.Connection(candidate, StatusParser.serverInfo(body))
        }

    override suspend fun fetchStatus(): NagiosResult<StatusSnapshot> = withBase(::fetchStatus)

    override suspend fun fetchService(ref: ObjectRef): NagiosResult<ServiceStatus> = withBase { base ->
        val params = objectParams("service", ref)
        api.query(base, STATUS_CGI, params).flatMap { body -> StatusParser.service(body).orMissing() }
    }

    override suspend fun fetchHost(hostName: String): NagiosResult<HostStatus> = withBase { base ->
        val params = objectParams("host", ObjectRef(hostName))
        api.query(base, STATUS_CGI, params).flatMap { body -> StatusParser.host(body).orMissing() }
    }

    override suspend fun fetchComments(ref: ObjectRef): NagiosResult<List<Comment>> = withBase { base ->
        api.query(base, STATUS_CGI, objectParams("commentlist", ref) + ("details" to "true")).map { body ->
            AnnotationParser.comments(body, ref).take(NagiosClient.MAX_ANNOTATIONS)
        }
    }

    override suspend fun fetchDowntimes(ref: ObjectRef): NagiosResult<List<Downtime>> = withBase { base ->
        api.query(base, STATUS_CGI, objectParams("downtimelist", ref) + ("details" to "true")).map { body ->
            AnnotationParser.downtimes(body, ref).take(NagiosClient.MAX_ANNOTATIONS)
        }
    }

    /** Runs [block] against the CGI directory, finding it first if this client has not yet. */
    private suspend fun <T> withBase(block: suspend (HttpUrl) -> NagiosResult<T>): NagiosResult<T> {
        val known = cgiBase
        val base = if (known != null) NagiosResult.Success(known) else connect().map { it.cgiBase }
        return when (base) {
            is NagiosResult.Failure -> base
            is NagiosResult.Success -> block(base.value)
        }
    }

    private fun objectParams(query: String, ref: ObjectRef): List<Pair<String, String>> = buildList {
        add("query" to query)
        add("formatoptions" to "enumerate")
        add("hostname" to ref.hostName)
        ref.description?.let { add("servicedescription" to it) }
    }

    /** Nagios answered successfully but with no record: the object is gone, or was renamed. */
    private fun <T : Any> T?.orMissing(): NagiosResult<T> =
        if (this != null) NagiosResult.Success(this) else NagiosResult.Failure(NagiosError.Api(0, "Missing", ""))

    /** All or nothing: half a picture of a monitoring system is worse than an honest error. */
    private suspend fun fetchStatus(base: HttpUrl): NagiosResult<StatusSnapshot> =
        when (val hosts = fetcher.fetchAll(base, "hostlist", emptyList(), parse = StatusParser::hosts)) {
            is NagiosResult.Failure -> hosts
            is NagiosResult.Success -> fetchServices(base, hosts.value)
        }

    private suspend fun fetchServices(base: HttpUrl, hosts: List<HostStatus>): NagiosResult<StatusSnapshot> =
        when (val problems = fetcher.fetchAll(base, "servicelist", PROBLEM_SERVICES, parse = StatusParser::services)) {
            is NagiosResult.Failure -> problems

            // Names and states of every service: small, and never trips the serialisation failure.
            is NagiosResult.Success ->
                fetcher.fetchAll(base, "servicelist", emptyList(), details = false, StatusParser::services).map { all ->
                    StatusSnapshot(
                        hosts = hosts,
                        serviceProblems = problems.value,
                        fetchedAt = clock.instant(),
                        serviceStates = all.map { ServiceStateEntry(it.hostName, it.description, it.state) },
                    )
                }
        }

    /**
     * Where the CGIs might live under what the user typed. Installs differ:
     * `/nagios/cgi-bin/` is the upstream default, `/cgi-bin/` is common behind
     * proxies, and a user may paste the CGI directory itself.
     */
    private fun cgiCandidates(): List<HttpUrl> {
        settings.cgiBase?.let { return listOf(it) }
        val base = settings.baseUrl.newBuilder().query(null).fragment(null).build().asDirectory()
        if (base.pathSegments.dropLast(1).lastOrNull() == CGI_DIR) return listOf(base)
        return listOfNotNull(
            base.resolve("$CGI_DIR/"),
            base.resolve("nagios/$CGI_DIR/").takeUnless { NAGIOS_DIR in base.pathSegments },
        )
    }

    private fun HttpUrl.asDirectory(): HttpUrl =
        if (pathSegments.last().isEmpty()) this else newBuilder().addPathSegment("").build()

    private companion object {
        const val STATUS_CGI = ResilientListFetcher.STATUS_CGI
        const val CGI_DIR = "cgi-bin"
        const val NAGIOS_DIR = "nagios"

        /** Space-separated list, as the CGI expects. */
        val PROBLEM_SERVICES = listOf("servicestatus" to "warning critical unknown")
    }
}
