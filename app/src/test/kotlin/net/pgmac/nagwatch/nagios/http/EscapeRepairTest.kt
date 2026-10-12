// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.pgmac.nagwatch.nagios.NagiosResult
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

    /** The repair, for a text that is within its allowance. */
    private fun repair(text: String): String = checkNotNull(repairInvalidEscapes(text)) { "over the allowance" }

    @Test
    fun `a bell and a vertical tab are rewritten as JSON has them`() {
        assertEquals("""x\u0007y""", repair("x" + backslash + "ay"))
        assertEquals("""x\u000by""", repair("x" + backslash + "vy"))
        assertEquals("""\u0007\u000b""", repair(backslash + "a" + backslash + "v"))
    }

    @Test
    fun `an escaped backslash before a letter is a backslash and a letter, and is left alone`() {
        // JSON `\\a` is one backslash then an a: no bell.
        assertEquals(backslash + backslash + "a", repair(backslash + backslash + "a"))
        assertEquals(backslash + backslash + "v", repair(backslash + backslash + "v"))
    }

    @Test
    fun `an escaped backslash and then a bell is repaired after the pair`() {
        // What Nagios writes for a real backslash followed by a bell.
        assertEquals(
            backslash + backslash + """\u0007""",
            repair(backslash + backslash + backslash + "a"),
        )
    }

    @Test
    fun `an even run of backslashes is escaped backslashes, and an odd run ends in an escape`() {
        (1..8).forEach { n ->
            val run = backslash.repeat(n)
            val expectedForA = if (n % 2 == 1) backslash.repeat(n - 1) + """\u0007""" else run + "a"
            assertEquals("$n backslashes then a", expectedForA, repair(run + "a"))
        }
    }

    @Test
    fun `every escape JSON has is left exactly as it is`() {
        val valid = """{"a":"\" \\ \/ \b \f \n \r \t \u0041 \u00e9 \ud83d\ude00"}"""

        assertSame(valid, repair(valid))
    }

    @Test
    fun `text with no backslash comes back as the same string, not a copy`() {
        val text = """{"result":{"type_code":0},"data":{"hostlist":{}}}"""

        assertSame(text, repair(text))
    }

    @Test
    fun `awkward ends and shapes do not throw`() {
        listOf("", backslash, backslash + backslash, "a" + backslash, backslash + "x", backslash + "\u0000", "\u0000")
            .forEach { text ->
                val repaired = repair(text)
                assertEquals("repairing twice is repairing once: \"$text\"", repaired, repair(repaired))
            }
    }

    @Test
    fun `a repair is four characters longer, so growth is the number of repairs times four`() {
        val body = (backslash + "a").repeat(1_000)

        assertEquals(body.length + 4 * 1_000, repair(body).length)
    }

    @Test
    fun `past the allowance a repair is the nearest valid escape, in the same length`() {
        val body = (backslash + "a") + (backslash + "v") + (backslash + "a") + (backslash + "v")

        val repaired = repairInvalidEscapes(body, maxRepairs = 2)

        // The first two are exact. The rest are \b and \f: no longer than what was sent.
        assertEquals("\\u0007" + "\\u000b" + backslash + "b" + backslash + "f", repaired)
        assertEquals(body.length + 4 * 2, repaired.length)
    }

    @Test
    fun `at the allowance every repair is exact, and the first one over is not`() {
        val atTheLimit = (backslash + "a").repeat(MAX_REPAIRS)

        assertEquals(6 * MAX_REPAIRS, repair(atTheLimit).length)
        assertEquals(6 * MAX_REPAIRS + 2, repair(atTheLimit + backslash + "v").length)
    }

    @Test
    fun `growth is capped whatever the body, and the result is still valid JSON`() {
        val body = "{\"a\":\"" + (backslash + "a").repeat(3 * MAX_REPAIRS) + "\"}"

        val repaired = repair(body)

        assertEquals(body.length + 4 * MAX_REPAIRS, repaired.length)
        assertEquals("still one key, and it parses", 1, (json.parseToJsonElement(repaired) as JsonObject).size)
    }

    @Test
    fun `the allowance counts repairs, not escapes, so valid ones before a bell do not use it up`() {
        val valid = (backslash + backslash).repeat(MAX_REPAIRS + 10)

        assertSame("nothing to repair is the same string", valid, repair(valid))
        assertEquals(
            "and the first bell after them is still exact",
            valid + backslash + "u0007",
            repair(valid + backslash + "a"),
        )
    }

    @Test
    fun `a body over the allowance is still read, not refused`() {
        val body = "{\"result\":{\"type_code\":0},\"data\":{\"a\":\"x\"}}"
            .replace("x", (backslash + "a").repeat(MAX_REPAIRS + 1))

        val result = interpretResponseBody(json, body)

        assertTrue(result is NagiosResult.Success)
    }

    @Test
    fun `a repaired string parses to the character it stood for`() {
        val text = """{"a":"x""" + backslash + "ay" + backslash + """vz"}"""

        val parsed = json.parseToJsonElement(repair(text)) as JsonObject

        assertEquals("x\u0007y\u000bz", (parsed["a"] as JsonPrimitive).content)
    }

    @Test
    fun `the characters are the ones that were meant, a real backslash included`() {
        val text = """{"a":"[""" + backslash + "a] [" + backslash + "v] [" + backslash + backslash + "a] [" +
            backslash + backslash + backslash + """a]"}"""

        val parsed = json.parseToJsonElement(repair(text)) as JsonObject

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
