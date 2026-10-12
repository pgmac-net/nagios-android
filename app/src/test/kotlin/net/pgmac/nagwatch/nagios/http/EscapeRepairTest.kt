// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two escapes Nagios writes that JSON does not have (pgmac-net/nagios-android#23).
 *
 * Strings here are written so that what is in the *text* is plain: a Kotlin string `"\\a"` is
 * a backslash and an `a`, which is what Nagios writes for a bell.
 */
class EscapeRepairTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val backslash = "\\"

    @Test
    fun `a bell and a vertical tab are rewritten as JSON has them`() {
        assertEquals("""x\u0007y""", repairInvalidEscapes("x" + backslash + "ay"))
        assertEquals("""x\u000by""", repairInvalidEscapes("x" + backslash + "vy"))
        assertEquals("""\u0007\u000b""", repairInvalidEscapes(backslash + "a" + backslash + "v"))
    }

    @Test
    fun `an escaped backslash before a letter is a backslash and a letter, and is left alone`() {
        // JSON `\\a` is one backslash then an a: no bell.
        assertEquals(backslash + backslash + "a", repairInvalidEscapes(backslash + backslash + "a"))
        assertEquals(backslash + backslash + "v", repairInvalidEscapes(backslash + backslash + "v"))
    }

    @Test
    fun `an escaped backslash and then a bell is repaired after the pair`() {
        // What Nagios writes for a real backslash followed by a bell.
        assertEquals(
            backslash + backslash + """\u0007""",
            repairInvalidEscapes(backslash + backslash + backslash + "a"),
        )
    }

    @Test
    fun `an even run of backslashes is escaped backslashes, and an odd run ends in an escape`() {
        (1..8).forEach { n ->
            val run = backslash.repeat(n)
            val expectedForA = if (n % 2 == 1) backslash.repeat(n - 1) + """\u0007""" else run + "a"
            assertEquals("$n backslashes then a", expectedForA, repairInvalidEscapes(run + "a"))
        }
    }

    @Test
    fun `every escape JSON has is left exactly as it is`() {
        val valid = """{"a":"\" \\ \/ \b \f \n \r \t \u0041 \u00e9 \ud83d\ude00"}"""

        assertSame(valid, repairInvalidEscapes(valid))
    }

    @Test
    fun `text with no backslash comes back as the same string, not a copy`() {
        val text = """{"result":{"type_code":0},"data":{"hostlist":{}}}"""

        assertSame(text, repairInvalidEscapes(text))
    }

    @Test
    fun `awkward ends and shapes do not throw`() {
        listOf("", backslash, backslash + backslash, "a" + backslash, backslash + "x", backslash + "\u0000", "\u0000")
            .forEach { text ->
                val repaired = repairInvalidEscapes(text)
                assertEquals("repairing twice is repairing once: \"$text\"", repaired, repairInvalidEscapes(repaired))
            }
    }

    @Test
    fun `a body of nothing but bells is three times its size, and no more`() {
        val body = (backslash + "a").repeat(1_000)

        assertEquals(3 * body.length, repairInvalidEscapes(body).length)
    }

    @Test
    fun `a repaired string parses to the character it stood for`() {
        val text = """{"a":"x""" + backslash + "ay" + backslash + """vz"}"""

        val parsed = json.parseToJsonElement(repairInvalidEscapes(text)) as JsonObject

        assertEquals("x\u0007y\u000bz", (parsed["a"] as JsonPrimitive).content)
    }

    @Test
    fun `the characters are the ones that were meant, a real backslash included`() {
        val text = """{"a":"[""" + backslash + "a] [" + backslash + "v] [" + backslash + backslash + "a] [" +
            backslash + backslash + backslash + """a]"}"""

        val parsed = json.parseToJsonElement(repairInvalidEscapes(text)) as JsonObject

        assertEquals("[\u0007] [\u000b] [\\a] [\\\u0007]", (parsed["a"] as JsonPrimitive).content)
    }

    @Test
    fun `a text that was not valid for another reason is still not valid`() {
        assertTrue(
            interpretResponseBody(json, "<html>" + backslash + "a</html>").let {
                it.toString().contains("NotNagios")
            },
        )
        assertTrue(
            interpretResponseBody(
                json,
                """{"result":{"type_code":0}""" + backslash + "a",
            ).toString().contains("NotNagios"),
        )
    }
}
