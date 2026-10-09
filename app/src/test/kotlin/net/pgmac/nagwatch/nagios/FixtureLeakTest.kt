// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are captured from a real Nagios and this repository is public.
 * scripts/sanitise_fixtures.py removes everything identifying; this test is the
 * backstop that fails the build if a raw or half-sanitised capture is committed.
 *
 * It cannot know anyone's real host names (listing them here would publish
 * them), so it checks shape instead: names must be the generic ones the
 * sanitiser produces, and nothing may look like a private address or a real
 * domain.
 */
class FixtureLeakTest {
    private val fixtures: List<File> = File("src/test/resources/fixtures").listFiles().orEmpty().sortedBy { it.name }

    @Test
    fun `there are fixtures to check`() {
        assertTrue("no fixtures found in ${File("src/test/resources/fixtures").absolutePath}", fixtures.size >= 8)
    }

    @Test
    fun `host names are the sanitiser's generic names`() {
        val offenders = fixtures.flatMap { file ->
            val data = json(file)["data"] as? JsonObject
            val hosts = keysOf(data?.get("hostlist")) + keysOf(data?.get("servicelist"))
            hosts.filterNot(GENERIC_HOST::matches).map { "${file.name}: host \"$it\"" }
        }

        assertTrue("not sanitised: $offenders", offenders.isEmpty())
    }

    @Test
    fun `service descriptions are the sanitiser's generic names`() {
        val offenders = fixtures.flatMap { file ->
            val services = (json(file)["data"] as? JsonObject)?.get("servicelist") as? JsonObject
            services.orEmpty().values
                .flatMap { keysOf(it) }
                .filterNot { it in GENERIC_SERVICES || GENERIC_SERVICE_OVERFLOW.matches(it) }
                .map { "${file.name}: service \"$it\"" }
        }

        assertTrue("not sanitised: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no private addresses, real domains or real user names`() {
        val offenders = fixtures.flatMap { file ->
            val text = file.readText()
            FORBIDDEN.flatMap { (what, pattern) ->
                pattern.findAll(text).map { it.value }.filterNot { it in ALLOWED }.map { "${file.name}: $what \"$it\"" }
            }
        }

        assertTrue("not sanitised: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the authenticated user is the generic one`() {
        val offenders = fixtures.mapNotNull { file ->
            val user = (json(file)["result"] as? JsonObject)?.get("user")?.toString()?.trim('"')
            if (user == null || user == "nagwatch") null else "${file.name}: user \"$user\""
        }

        assertTrue("not sanitised: $offenders", offenders.isEmpty())
    }

    private fun json(file: File) = TEST_JSON.parseToJsonElement(file.readText()) as JsonObject

    private fun keysOf(element: JsonElement?): Set<String> = (element as? JsonObject)?.keys.orEmpty()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private companion object {
        val GENERIC_HOST = Regex("""host\d{2,}""")
        val GENERIC_SERVICE_OVERFLOW = Regex("""Service \d{2,}""")

        /** Keep in step with SERVICE_NAMES in scripts/sanitise_fixtures.py. */
        val GENERIC_SERVICES = setOf(
            "PING", "SSH", "HTTP", "Disk /", "Load", "Memory", "Swap", "NTP",
            "DNS", "Processes", "Users", "Uptime", "Disk /var", "HTTPS certificate",
        )

        val FORBIDDEN = mapOf(
            "private address" to
                Regex("""\b(10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)\b"""),
            "domain name" to Regex(
                """\b[a-z0-9-]+(\.[a-z0-9-]+)*\.(net|com|org|au|io|int|lan|local|internal|home)\b""",
            ),
        )

        /** `statusjson.cgi` is the CGI's own name, which Nagios echoes in every result. */
        val ALLOWED = setOf("statusjson.cgi")
    }
}
