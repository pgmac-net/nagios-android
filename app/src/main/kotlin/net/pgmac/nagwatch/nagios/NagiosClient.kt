// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.http.ConnectionInterceptor
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.http.ResilientListFetcher
import net.pgmac.nagwatch.nagios.http.StatusParser
import net.pgmac.nagwatch.nagios.model.ServerInfo
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

    /** Every host, plus every service currently in a problem state. */
    suspend fun fetchStatus(): NagiosResult<StatusSnapshot>

    data class Connection(val cgiBase: HttpUrl, val server: ServerInfo)
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

    override suspend fun fetchStatus(): NagiosResult<StatusSnapshot> {
        val known = cgiBase
        val base = if (known != null) NagiosResult.Success(known) else connect().map { it.cgiBase }
        return when (base) {
            is NagiosResult.Failure -> base
            is NagiosResult.Success -> fetchStatus(base.value)
        }
    }

    /** All or nothing: half a picture of a monitoring system is worse than an honest error. */
    private suspend fun fetchStatus(base: HttpUrl): NagiosResult<StatusSnapshot> =
        when (val hosts = fetcher.fetchAll(base, "hostlist", emptyList(), StatusParser::hosts)) {
            is NagiosResult.Failure -> hosts

            is NagiosResult.Success ->
                fetcher.fetchAll(base, "servicelist", PROBLEM_SERVICES, StatusParser::services).map { services ->
                    StatusSnapshot(hosts = hosts.value, serviceProblems = services, fetchedAt = clock.instant())
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
