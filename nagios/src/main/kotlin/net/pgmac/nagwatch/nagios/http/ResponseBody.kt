// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult

/**
 * What a response body means: the JSON, if it is Nagios' JSON, or the reason it is not.
 *
 * Nagios reports its own errors as HTTP 200 with a non-zero `result.type_code`, so a
 * 200 proves nothing and the body is always read. This is the one place text from a
 * server becomes data; it is a plain function of its input so that it can be fuzzed.
 * Whatever the text is, the answer is a result, never an exception.
 */
internal fun interpretResponseBody(json: Json, text: String): NagiosResult<JsonObject> {
    val body = parseJsonObject(json, repairInvalidEscapes(text))
    val result = body?.get("result") as? JsonObject
    val code = (result?.get("type_code") as? JsonPrimitive)?.smallWholeNumber()
    return when {
        body == null || result == null || code == null -> NagiosResult.Failure(NagiosError.NotNagios)
        code == SUCCESS -> NagiosResult.Success(body)
        else -> NagiosResult.Failure(NagiosError.Api(code, result.text("type_text"), result.text("message")))
    }
}

/**
 * Rewrites the two escapes Nagios writes that JSON does not have.
 *
 * Nagios' JSON writer escapes a bell as `\a` and a vertical tab as `\v`, which RFC 8259 does not
 * allow. Left alone, one check printing either character makes the whole response unparseable:
 * the poll fails, and the app says something other than Nagios is answering. Measured on Nagios
 * Core 4.5.3, by passing every control character through a check; all the others are written as
 * a valid escape or are legal as they stand (pgmac-net/nagios-android#23).
 *
 * Escapes are read left to right, a backslash together with the character after it, because
 * that is how JSON reads them. A real backslash is written `\\`, so `\\a` is a backslash and then
 * an `a`, and is left alone; `\\\a` is a backslash and then a bell, and is repaired.
 * Looking for "a backslash before an a" would get both wrong.
 *
 * A repair makes the text four characters longer, and the body's size limit was applied to what
 * the server sent, before this. So the repair has an allowance of its own: the first
 * [maxRepairs] are exact, and past that a bell or a vertical tab is written as the nearest valid
 * two-character escape (`\b`, `\f`), which is the same length. Growth is therefore at most four
 * times [maxRepairs] characters, whatever the body. A real check prints a bell now and then, not
 * tens of thousands, so nothing real is ever approximated. It never refuses: failing the poll
 * for one check's output is the very thing this exists to prevent.
 *
 * A text with nothing to repair is returned as the same string, without copying.
 */
internal fun repairInvalidEscapes(text: String, maxRepairs: Int = MAX_REPAIRS): String {
    var repaired: StringBuilder? = null
    var repairs = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != '\\' || i + 1 >= text.length) {
            repaired?.append(c)
            i++
            continue
        }
        val escape = text[i + 1]
        val replacement = REPAIRS[escape]
        if (replacement != null) {
            repairs++
            if (repaired == null) repaired = StringBuilder(text.length + REPAIR_HEADROOM).append(text, 0, i)
        }
        val written = when {
            replacement == null -> "\\$escape"
            repairs <= maxRepairs -> replacement
            else -> NEAREST_VALID.getValue(escape)
        }
        repaired?.append(written)
        i += 2
    }
    return repaired?.toString() ?: text
}

/** How many invalid escapes one body may have repaired exactly: 65,536 is at most 256 KiB of growth. */
internal const val MAX_REPAIRS = 65_536

/** Room for a few repairs without growing the buffer. A guess, and only a guess: it is not a limit. */
private const val REPAIR_HEADROOM = 16

/** What each invalid escape becomes past the allowance: the neighbouring control character, in the same length. */
private val NEAREST_VALID = mapOf('a' to "\\b", 'v' to "\\f")

/** What each invalid escape becomes: the same character, written as JSON has it. */
private val REPAIRS = mapOf('a' to "\\u0007", 'v' to "\\u000b")

/**
 * The text as a JSON object, or null if it is not JSON, not an object, or nests deeper
 * than anything Nagios writes.
 *
 * The depth check is not optional. The JSON reader recurses once per level, so a body
 * of a hundred thousand `[` (well inside the size limit) overflows the stack, which is
 * an `Error`, not an exception, and takes the whole app with it. Nagios' own output is
 * about six levels deep; [MAX_NESTING] is generous.
 */
internal fun parseJsonObject(json: Json, text: String): JsonObject? {
    if (nestsDeeperThan(text, MAX_NESTING)) return null
    return try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

/**
 * Whether [text] opens more than [limit] arrays or objects inside one another. Looks at
 * brackets outside strings only, in one pass and without recursing, so it is safe on
 * input of any depth. It does not check that the text is valid JSON: that is the
 * parser's job, once it is known to be safe to give it.
 */
internal fun nestsDeeperThan(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            inString -> inString = c != '"'
            c == '"' -> inString = true
            c == '[' || c == '{' -> if (++depth > limit) return true
            (c == ']' || c == '}') && depth > 0 -> depth--
        }
    }
    return false
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

private const val SUCCESS = 0

/** Nagios' JSON CGIs nest about six deep: data, a list, an object, a detail. */
internal const val MAX_NESTING = 32
