// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's own client against the throwaway test Nagios, whose "Control chars" check prints a
 * bell and a vertical tab (pgmac-net/nagios-android#23). Skipped unless asked for, and never run
 * in CI. This is the check that does not depend on what a file says Nagios writes:
 *
 *     scripts/test-nagios/live-test.sh
 *
 * Only reads. It still refuses anything that is not the made-up instance, so that it can never
 * be pointed at a real one by mistake.
 */
class ControlCharactersLiveTest {
    private val url = System.getenv("NAGWATCH_TEST_NAGIOS").orEmpty()
    private val factory = NagiosClientFactory(
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build(),
        Json { ignoreUnknownKeys = true },
        Dispatchers.IO,
        Clock.systemUTC(),
    )

    @Before
    fun onlyTheTestInstance() {
        assumeTrue("set NAGWATCH_TEST_NAGIOS (scripts/test-nagios/live-test.sh does)", url.isNotEmpty())
        val host = url.toHttpUrl().host
        check(host == "127.0.0.1" || host == "localhost") { "refusing: $host is not this machine" }
    }

    @Test
    fun `a poll of the test Nagios succeeds with the control characters in it, and shows the record whole`() {
        val client = factory.create(
            ConnectionSettings(
                baseUrl = url.toHttpUrl(),
                username = "viewer",
                // Not a secret: the test Nagios' made-up users, see scripts/test-nagios/start.sh.
                password = "viewer-pw",
                allowCleartext = true,
            ),
        )

        val snapshot = runBlocking { client.fetchStatus() }.valueOrFail()

        val record = snapshot.serviceProblems.single { it.description == "Control chars" }
        assertTrue("bell is in the output", "\u0007" in record.check.pluginOutput)
        assertTrue("vertical tab is in the output", "\u000b" in record.check.pluginOutput)
        assertTrue("a real backslash before an a survives", "backslash-a[\\a]" in record.check.pluginOutput)
        assertTrue("a real backslash before a bell survives", "backslash-bell[\\\u0007]" in record.check.pluginOutput)
        assertEquals("shown whole, not degraded", 0, snapshot.degradedCount)
        assertTrue("and the made-up host is still there", snapshot.hosts.any { it.name == "web01" })
    }
}
