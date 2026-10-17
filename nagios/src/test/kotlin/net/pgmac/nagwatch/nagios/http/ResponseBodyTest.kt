// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseBodyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a success body is returned as it is`() {
        val result = interpretResponseBody(json, """{"result":{"type_code":0},"data":{"x":1}}""")

        assertTrue(result is NagiosResult.Success)
    }

    @Test
    fun `an error from Nagios keeps its code, type and message`() {
        val text = """{"result":{"type_code":3,"type_text":"Error","message":"no such query"}}"""

        val result = interpretResponseBody(json, text) as NagiosResult.Failure

        assertEquals(NagiosError.Api(3, "Error", "no such query"), result.error)
    }

    @Test
    fun `text that is not Nagios' JSON is reported as not Nagios`() {
        listOf("", "<html>login</html>", "[]", "{}", """{"result":[]}""", """{"result":{"type_code":null}}""")
            .forEach { text ->
                val result = interpretResponseBody(json, text) as NagiosResult.Failure
                assertEquals(text, NagiosError.NotNagios, result.error)
            }
    }

    @Test
    fun `a code written as a string is read as the number, which Nagios never does and does no harm`() {
        // Documented rather than assumed: kotlinx.serialization's intOrNull parses string content.
        val result = interpretResponseBody(json, """{"result":{"type_code":"0"}}""")

        assertTrue(result is NagiosResult.Success)
    }

    @Test
    fun `a body nested a hundred thousand deep is refused, not a stack overflow`() {
        // Well inside the 8 MiB body limit, and the JSON reader recurses once per level.
        listOf("[".repeat(100_000), """{"a":""".repeat(50_000), "[{".repeat(50_000)).forEach { text ->
            val result = interpretResponseBody(json, text) as NagiosResult.Failure
            assertEquals(NagiosError.NotNagios, result.error)
            assertNull(parseJsonObject(json, text))
        }
    }

    @Test
    fun `nesting counts brackets outside strings only`() {
        val inString = """{"result":{"type_code":0},"data":{"plugin_output":"${"[".repeat(1000)}"}}"""

        assertFalse(nestsDeeperThan(inString, MAX_NESTING))
        assertNotNull(parseJsonObject(json, inString))
    }

    @Test
    fun `brackets in a string are not remembered once the string has ended`() {
        // Real nesting comes after the string. If the string's brackets had been counted, the
        // five real levels below would be added to a thousand and the document refused.
        val text = """{"a":"${"[".repeat(1000)}","b":[[[[[]]]]]}"""

        assertFalse(nestsDeeperThan(text, MAX_NESTING))
        assertNotNull(parseJsonObject(json, text))
    }

    @Test
    fun `an escaped quote does not end a string early`() {
        // If \" ended the string, the brackets after it would be counted as structure.
        val text = """{"a":"say \"${"[".repeat(1000)}\" please"}"""

        assertFalse(nestsDeeperThan(text, MAX_NESTING))
        assertNotNull(parseJsonObject(json, text))
    }

    @Test
    fun `an escaped backslash before a quote does end the string`() {
        // "\\" is a backslash, then the quote closes the string, so these brackets are structure.
        val text = """{"a":"\\"${"[".repeat(1000)}"""

        assertTrue(nestsDeeperThan(text, MAX_NESTING))
    }

    @Test
    fun `the limit is exact`() {
        assertFalse(nestsDeeperThan("[".repeat(MAX_NESTING), MAX_NESTING))
        assertTrue(nestsDeeperThan("[".repeat(MAX_NESTING + 1), MAX_NESTING))
        // Closing brackets bring the depth back down, so a long flat document is fine.
        assertFalse(nestsDeeperThan("[]".repeat(100_000), MAX_NESTING))
    }

    @Test
    fun `real responses are far inside the limit`() {
        val deepest = listOf(
            "hostlist_details",
            "servicelist_problems_details",
            "commentlist_by_host",
            "service_detail",
        ).maxOf { name ->
            val text = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name.json")).readText()
            var depth = 0
            var max = 0
            for (c in text) {
                if (c == '{' || c == '[') {
                    max = maxOf(max, ++depth)
                } else if (c == '}' || c == ']') {
                    depth--
                }
            }
            max
        }

        assertTrue("the deepest fixture nests $deepest", deepest <= MAX_NESTING / 2)
    }
}
