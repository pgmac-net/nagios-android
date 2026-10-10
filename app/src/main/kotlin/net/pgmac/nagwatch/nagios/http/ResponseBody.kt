// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
    val body = parseJsonObject(json, text)
    val result = body?.get("result") as? JsonObject
    val code = (result?.get("type_code") as? JsonPrimitive)?.intOrNull
    return when {
        body == null || result == null || code == null -> NagiosResult.Failure(NagiosError.NotNagios)
        code == SUCCESS -> NagiosResult.Success(body)
        else -> NagiosResult.Failure(NagiosError.Api(code, result.text("type_text"), result.text("message")))
    }
}

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
