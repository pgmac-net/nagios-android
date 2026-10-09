// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.io.File
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.pgmac.nagwatch.nagios.http.ConnectionInterceptor
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.http.ResilientListFetcher
import net.pgmac.nagwatch.nagios.http.StatusParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the real client against a real Nagios. Skipped unless asked for, and
 * never run in CI: there are no credentials there and there must not be.
 *
 *     scripts/live-smoke-test.sh https://nagios.example.org/nagios
 *
 * Credentials come from `~/.config/nagwatch/dev.env` (`USER=` and `PASS=`,
 * optionally `ACCESS_CLIENT_ID=` and `ACCESS_CLIENT_SECRET=`); a read-only
 * Nagios user is enough. Output is counts only: no host or service names are
 * printed, so a run can be pasted into an issue.
 */
class LiveSmokeTest {
    private lateinit var settings: ConnectionSettings
    private val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(60)).build()

    @Before
    fun loadSettings() {
        assumeTrue("live test not requested", System.getProperty("nagwatch.live") == "true")
        val url = System.getenv("NAGWATCH_URL").orEmpty()
        val envFile =
            File(System.getenv("NAGWATCH_ENV") ?: "${System.getProperty("user.home")}/.config/nagwatch/dev.env")
        assumeTrue("set NAGWATCH_URL to the Nagios base URL", url.isNotEmpty())
        assumeTrue("no credentials at $envFile", envFile.isFile)

        val values = envFile.readLines()
            .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key.trim() to value.trim().trim('"', '\'') }
        settings = ConnectionSettings(
            baseUrl = url.toHttpUrl(),
            username = values.getValue("USER"),
            password = values.getValue("PASS"),
            // Access credentials are never sent over http, so a LAN http:// run leaves them out,
            // exactly as a profile would have to.
            accessClientId = values["ACCESS_CLIENT_ID"].takeIf { url.startsWith("https://") },
            accessClientSecret = values["ACCESS_CLIENT_SECRET"].takeIf { url.startsWith("https://") },
            allowCleartext = url.startsWith("http://"),
        )
    }

    @Test
    fun `connects, polls and classifies against a real instance`() {
        val client = NagiosClientFactory(http, TEST_JSON, Dispatchers.IO, Clock.systemUTC()).create(settings)

        val connection = runBlocking { client.connect() }.valueOrFail()
        val snapshot = runBlocking { client.fetchStatus() }.valueOrFail()
        val report = ProblemClassifier.classify(snapshot)

        println("live: Nagios ${connection.server.version}, CGI path ${connection.cgiBase.encodedPath}")
        println("live: ${snapshot.hosts.size} hosts, ${snapshot.serviceProblems.size} service problems fetched")
        println("live: unhandled ${report.counts}, handled ${report.handled.size}, degraded ${report.degradedCount}")
        assertTrue("no hosts returned", snapshot.hosts.isNotEmpty())
        assertTrue(connection.server.version.isNotEmpty())
    }

    /**
     * The failure that shaped [ResilientListFetcher]: on an instance where one
     * service cannot be serialised, a full detailed service list must still
     * come back, with that record degraded rather than the whole fetch lost.
     */
    @Test
    fun `a full detailed service list survives an unserialisable record`() {
        val client = NagiosClientFactory(http, TEST_JSON, Dispatchers.IO, Clock.systemUTC()).create(settings)
        val cgiBase = runBlocking { client.connect() }.valueOrFail().cgiBase
        val policed = http.newBuilder().addInterceptor(ConnectionInterceptor(settings)).build()
        val fetcher = ResilientListFetcher(NagiosApi(policed, TEST_JSON, Dispatchers.IO))

        val services = runBlocking {
            fetcher.fetchAll(cgiBase, "servicelist", emptyList(), StatusParser::services)
        }.valueOrFail()

        println("live: ${services.size} services in full, ${services.count { !it.check.detailsAvailable }} degraded")
        assertTrue("no services returned", services.isNotEmpty())
    }
}
