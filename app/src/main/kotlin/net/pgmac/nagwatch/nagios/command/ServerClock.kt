// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a Nagios server writes, and reads, a date and time: its `date_format` setting.
 *
 * The first two differ only in the order of day and month, and nothing but a date where
 * the two numbers differ can tell them apart.
 */
enum class ServerDateFormat(pattern: String) {
    /** `MM-DD-YYYY HH:MM:SS`. Nagios' default. */
    US("MM-dd-uuuu HH:mm:ss"),

    /** `DD-MM-YYYY HH:MM:SS`. */
    EURO("dd-MM-uuuu HH:mm:ss"),

    /** `YYYY-MM-DD HH:MM:SS`. */
    ISO8601("uuuu-MM-dd HH:mm:ss"),

    /** `YYYY-MM-DDTHH:MM:SS`. */
    STRICT_ISO8601("uuuu-MM-dd'T'HH:mm:ss"),
    ;

    // Strict: 31 February and month 13 are not dates, and a year is four digits.
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    internal fun write(local: LocalDateTime): String = formatter.format(local)

    /** [text] read as this format, or null if it is not a date and time written this way. */
    internal fun read(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text, formatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * What is needed to tell a Nagios server a time, in the only way it accepts one: as text,
 * in its own date format and its own local time.
 *
 * Nagios' `cmd.cgi` takes times as strings and reads them with `sscanf` according to the
 * server's `date_format`, then `mktime` in the server's timezone. Neither setting is
 * reported anywhere in its JSON API. Getting the order of day and month wrong does not
 * fail: 3 April is read as 4 March, and a downtime lands on the wrong day in silence.
 *
 * So nothing is assumed. The command form Nagios serves comes pre-filled with "now" in
 * exactly that format and timezone, and the JSON API gives the same moment as a number.
 * Comparing the two gives the format and the offset. See docs/adr/0006.
 *
 * [format] is null when the two orders could not be told apart: the form showed a day
 * equal to its month, and nothing was remembered.
 */
data class ServerClock(
    val format: ServerDateFormat?,
    /**
     * The server's local time minus UTC, as it is right now. A time on the far side of a
     * daylight-saving change will be written an hour out: the app cannot know the server's
     * zone rules, only what its clock says today.
     */
    val offset: ZoneOffset,
    /** "Now" exactly as the server wrote it. Sent back unchanged where a command only needs "now". */
    val nowText: String,
) {
    /**
     * [instant] as this server expects it, or null if that cannot be written without
     * knowing the order of day and month.
     *
     * When the format is unknown, a date whose day and month are the same number is still
     * safe to write: it reads the same either way.
     */
    fun format(instant: Instant): String? {
        val local = LocalDateTime.ofEpochSecond(instant.epochSecond, 0, offset)
        return when {
            format != null -> format.write(local)
            local.dayOfMonth == local.monthValue -> ServerDateFormat.US.write(local)
            else -> null
        }
    }

    companion object {
        /**
         * Works out the server's clock from what its command form showed.
         *
         * @param start the form's pre-filled start time: the server's "now", as text.
         * @param end the form's pre-filled end time, two hours later, when the form has one.
         *   On a day whose number equals its month this is the only thing on the page that
         *   can show the order, and only if those two hours cross midnight.
         * @param serverNow the same moment from the JSON API.
         * @param remembered the format seen on an earlier, unambiguous day, if any.
         * @return null if [start] is not a time in any format Nagios writes, or does not
         *   agree with [serverNow] under any of them.
         */
        fun read(start: String, end: String?, serverNow: Instant, remembered: ServerDateFormat?): ServerClock? {
            val text = start.trim()
            // Every format under which the text is a time that could be "now" on this server.
            val readings = ServerDateFormat.entries.mapNotNull { format ->
                format.read(text)?.let { local -> offsetOf(local, serverNow)?.let { Reading(format, local, it) } }
            }
            val offset = readings.firstOrNull()?.offset ?: return null
            // More than one reading fits only when day and month are the same number, and then
            // they agree on the offset. What the server shows today outranks what was remembered:
            // settings change.
            val format = readings.singleOrNull()?.format
                ?: readings.singleOrNull { it.endsTwoHoursLater(end) }?.format
                ?: remembered?.takeIf { it in readings.map(Reading::format) }
            return ServerClock(format, offset, text)
        }

        private class Reading(val format: ServerDateFormat, val local: LocalDateTime, val offset: ZoneOffset) {
            /** True if [end], read the same way, is the form's two hours on and shows the order itself. */
            fun endsTwoHoursLater(end: String?): Boolean {
                val endLocal = end?.let { format.read(it.trim()) } ?: return false
                return Duration.between(local, endLocal) == FORM_WINDOW && endLocal.dayOfMonth != endLocal.monthValue
            }
        }

        /** The pre-filled downtime window in Nagios' form is always this long. */
        private val FORM_WINDOW: Duration = Duration.ofHours(2)

        /** Time zones are whole quarter hours from UTC. */
        private const val QUARTER_HOUR_SECONDS = 900L

        /** How far the form's "now" and the JSON API's may differ: two requests, a moment apart. */
        private const val TOLERANCE_SECONDS = 120L

        /**
         * The offset that makes [local] the same moment as [serverNow], if there is a believable
         * one. A reading with day and month swapped is weeks out, so this is also what rejects it.
         */
        private fun offsetOf(local: LocalDateTime, serverNow: Instant): ZoneOffset? {
            val seconds = local.toEpochSecond(ZoneOffset.UTC) - serverNow.epochSecond
            val rounded = (seconds.toDouble() / QUARTER_HOUR_SECONDS).roundToLong() * QUARTER_HOUR_SECONDS
            val believable = abs(seconds - rounded) <= TOLERANCE_SECONDS && abs(rounded) <= ZoneOffset.MAX.totalSeconds
            return if (believable) ZoneOffset.ofTotalSeconds(rounded.toInt()) else null
        }
    }
}
