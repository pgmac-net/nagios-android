// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import net.pgmac.nagwatch.nagios.http.interpretResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A check whose output holds a bell and a vertical tab (pgmac-net/nagios-android#23).
 *
 * The responses are real: captured from the test Nagios in `scripts/test-nagios`, whose
 * "Control chars" service prints these characters. Before the repair, each of them was a response
 * that no JSON parser would take, and a poll that contained one failed whole.
 */
class ControlCharactersTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun `the captured responses are not valid JSON, which is the whole problem`() {
        listOf("service_control_chars.json", "servicelist_details_control_chars.json").forEach { name ->
            val failed = runCatching { TEST_JSON.parseToJsonElement(Fixtures.invalid(name)) }.isFailure
            assertTrue("$name should be what a strict parser rejects, or it proves nothing", failed)
        }
    }

    @Test
    fun `the captured record parses, with its output whole and the right characters in it`() {
        val result = interpretResponseBody(TEST_JSON, Fixtures.invalid("service_control_chars.json"))

        val body = (result as NagiosResult.Success).value
        val output = body.service().text("plugin_output")
        // A bell, a vertical tab, a real backslash then an a, a real backslash then a bell.
        assertEquals(
            "Control characters: bell[\u0007] vt[\u000b] backslash-a[\\a] backslash-bell[\\\u0007] end",
            output,
        )
    }

    @Test
    fun `the captured list parses, and the other services in it are what they were`() {
        val result = interpretResponseBody(TEST_JSON, Fixtures.invalid("servicelist_details_control_chars.json"))

        val services = ((result as NagiosResult.Success).value["data"] as JsonObject)["servicelist"] as JsonObject
        val web01 = services["web01"] as JsonObject
        assertEquals(setOf("Control chars", "Disk /", "HTTP", "Load"), web01.keys)
        assertTrue((web01["Disk /"] as JsonObject).text("plugin_output").contains("DISK CRITICAL - made up"))
    }

    @Test
    fun `a poll that includes the check succeeds, and the record shows with its output`() {
        server.respond { request ->
            when (request.param("query")) {
                "programstatus" -> ok(Fixtures.text("programstatus.json"))
                "hostlist" -> ok(Fixtures.text("hostlist_details.json"))
                "servicelist" -> ok(Fixtures.invalid("servicelist_details_control_chars.json"))
                else -> ok(Fixtures.text("error_invalid_option.json"))
            }
        }

        val snapshot = runBlocking {
            testFactory().create(settingsFor(server.url("/"), cgiBase = server.url("/"))).fetchStatus()
        }
            .valueOrFail()

        val record = snapshot.serviceProblems.single { it.description == "Control chars" }
        assertTrue("bell" in record.check.pluginOutput && "\u0007" in record.check.pluginOutput)
        assertTrue("\u000b" in record.check.pluginOutput)
        assertTrue("a real backslash survives", "backslash-a[\\a]" in record.check.pluginOutput)
        assertFalse("shown whole, not as a degraded record", snapshot.degradedCount > 0)
    }

    private fun JsonObject.service(): JsonObject = (this["data"] as JsonObject)["service"] as JsonObject

    private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).content
}
