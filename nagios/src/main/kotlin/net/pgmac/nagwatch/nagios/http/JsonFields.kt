// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Tolerant field access for Nagios JSON. A missing or oddly typed field becomes
 * a neutral default rather than failing a whole poll: different Nagios versions
 * send different fields, and one bad value must not blank the screen.
 */

internal fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonElement.word(): String = (this as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

/**
 * A whole number, or null if the value is not one.
 *
 * Not kotlinx's own `longOrNull` and `intOrNull`: handed a string such as `"542` (a quote and
 * then digits) they throw `StringIndexOutOfBoundsException` instead of answering null. A
 * fuzzer found it. Nagios writes whole numbers plainly, so nothing is lost by being strict.
 */
internal fun JsonPrimitive.wholeNumber(): Long? = content.toLongOrNull()

/** As [wholeNumber], for a value that must fit an Int. One that does not is not a number here: it never wraps. */
internal fun JsonPrimitive.smallWholeNumber(): Int? = content.toIntOrNull()

internal fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.smallWholeNumber() ?: 0

internal fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.wholeNumber() ?: 0

internal fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

/** Nagios sends epoch milliseconds, and 0 for "never". */
internal fun JsonObject.instant(key: String): Instant? =
    (this[key] as? JsonPrimitive)?.wholeNumber()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
