// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Numbers are read from whatever a server sends, and a server can send anything. A fuzzer
 * found that kotlinx's own lenient readers throw on a string that starts with a quote; these
 * are the cases that must give a default, never an exception and never a different number.
 */
class JsonFieldsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a number is read whether Nagios wrote it bare or in quotes`() {
        val fields = parse("""{"a": 3, "b": "4", "t": 1765432100000}""")

        assertEquals(3, fields.int("a"))
        assertEquals(4, fields.int("b"))
        assertEquals(1765432100000, fields.long("t"))
        assertEquals(Instant.ofEpochMilli(1765432100000), fields.instant("t"))
    }

    @Test
    fun `anything that is not a whole number gives the default, not an exception`() {
        val odd = listOf("\"\\\"542\"", "\"\"", "\"-\"", "\"1e3\"", "\"12abc\"", "1.5", "true", "null", "[1]", "{}")

        odd.forEach { value ->
            val fields = parse("""{"n": $value}""")
            assertEquals("int of $value", 0, fields.int("n"))
            assertEquals("long of $value", 0L, fields.long("n"))
            assertNull("instant of $value", fields.instant("n"))
        }
    }

    @Test
    fun `a number too large for its kind is not one, and never wraps round into another`() {
        // 2^32 as an Int would be 0.
        val fields = parse("""{"n": 4294967296, "huge": 99999999999999999999999}""")

        assertEquals(0, fields.int("n"))
        assertEquals(4294967296, fields.long("n"))
        assertEquals(0L, fields.long("huge"))
    }

    @Test
    fun `an error code that is not a small number is not mistaken for success`() {
        val bodies = listOf(
            """{"result": {"type_code": "\"542"}}""",
            // Would be 0, "success", if it were allowed to wrap.
            """{"result": {"type_code": 4294967296}}""",
            """{"result": {"type_code": ""}}""",
        )

        bodies.forEach { body ->
            assertEquals(body, NagiosResult.Failure(NagiosError.NotNagios), interpretResponseBody(json, body))
        }
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}
