// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Working out how a server writes a time. The cost of getting this wrong is a downtime
 * on the wrong day with nothing to say so, which is why it has more tests than it has code.
 */
class ServerClockTest {
    @Test
    fun `the four formats are each recognised from a real form`() {
        // What each form showed, and the moment the server's JSON clock gave for it.
        val seen = mapOf(
            "form_downtime_iso8601" to ("2026-10-10T13:33:46Z" to ServerDateFormat.ISO8601),
            "form_downtime_strict_iso8601" to ("2026-10-10T13:35:46Z" to ServerDateFormat.STRICT_ISO8601),
        )
        seen.forEach { (name, expected) ->
            val clock = read(name, Instant.parse(expected.first))
            assertEquals(name, expected.second, clock?.format)
            assertEquals(name, ZoneOffset.UTC, clock?.offset)
        }
    }

    @Test
    fun `on a day whose number equals its month, month-day and day-month cannot be told apart`() {
        // Both of these were captured on 10 October, one from a US-format server and one European.
        val us = read("form_downtime_us", Instant.parse("2026-10-10T13:29:32Z"))
        val euro = read("form_downtime_euro", Instant.parse("2026-10-10T13:31:45Z"))

        assertNull("the page looks the same either way", us?.format)
        assertNull(euro?.format)
        assertEquals("but the offset is known all the same", ZoneOffset.UTC, us?.offset)
        assertEquals("10-10-2026 13:29:32", us?.nowText)
    }

    @Test
    fun `unless the form's own two hours cross midnight, which shows the order`() {
        // Captured in Brisbane at 23:37 on 10 October: the form's end time is 01:37 on the 11th.
        val clock = read("form_downtime_us_crossing_midnight", Instant.parse("2026-10-10T13:37:48Z"))

        assertEquals(ServerDateFormat.US, clock?.format)
        assertEquals(ZoneOffset.ofHours(10), clock?.offset)
    }

    @Test
    fun `the same crossing read on a European server gives European`() {
        val clock = ServerClock.read(
            "10-10-2026 23:37:48",
            "11-10-2026 01:37:48",
            Instant.parse("2026-10-10T13:37:48Z"),
            null,
        )

        assertEquals(ServerDateFormat.EURO, clock?.format)
    }

    @Test
    fun `on any other day the order is plain from the date itself`() {
        val fourthOfMarch = Instant.parse("2026-03-04T09:00:00Z")

        assertEquals(ServerDateFormat.US, ServerClock.read("03-04-2026 09:00:00", null, fourthOfMarch, null)?.format)
        assertEquals(ServerDateFormat.EURO, ServerClock.read("04-03-2026 09:00:00", null, fourthOfMarch, null)?.format)
        // Day over 12 leaves only one reading that is a date at all.
        val lateMarch = Instant.parse("2026-03-25T09:00:00Z")
        assertEquals(ServerDateFormat.US, ServerClock.read("03-25-2026 09:00:00", null, lateMarch, null)?.format)
        assertEquals(ServerDateFormat.EURO, ServerClock.read("25-03-2026 09:00:00", null, lateMarch, null)?.format)
    }

    @Test
    fun `a remembered format is used only when today cannot show one`() {
        val tenthOfOctober = Instant.parse("2026-10-10T13:29:32Z")
        val ambiguous = "10-10-2026 13:29:32"

        assertEquals(
            ServerDateFormat.EURO,
            ServerClock.read(ambiguous, null, tenthOfOctober, ServerDateFormat.EURO)?.format,
        )
        assertEquals(
            ServerDateFormat.US,
            ServerClock.read(ambiguous, null, tenthOfOctober, ServerDateFormat.US)?.format,
        )
    }

    @Test
    fun `what the server shows today outranks what was remembered`() {
        val fourthOfMarch = Instant.parse("2026-03-04T09:00:00Z")

        // Remembered European; the server now plainly writes month first. Its setting was changed.
        val clock = ServerClock.read("03-04-2026 09:00:00", null, fourthOfMarch, ServerDateFormat.EURO)

        assertEquals(ServerDateFormat.US, clock?.format)
    }

    @Test
    fun `a remembered format that cannot be what is on the page is not used`() {
        val tenthOfOctober = Instant.parse("2026-10-10T13:29:32Z")

        // Remembered ISO, but the page is plainly not ISO. Better not to know than to be wrong.
        assertNull(ServerClock.read("10-10-2026 13:29:32", null, tenthOfOctober, ServerDateFormat.ISO8601)?.format)
    }

    @Test
    fun `the offset is read for zones east, west and at odd quarters of an hour`() {
        val now = Instant.parse("2026-03-25T12:00:00Z")
        mapOf(
            "2026-03-25 22:00:00" to ZoneOffset.ofHours(10),
            "2026-03-25 08:00:00" to ZoneOffset.ofHours(-4),
            "2026-03-25 17:30:00" to ZoneOffset.ofHoursMinutes(5, 30),
            "2026-03-25 17:45:00" to ZoneOffset.ofHoursMinutes(5, 45),
            "2026-03-26 01:00:00" to ZoneOffset.ofHours(13),
            "2026-03-25 00:00:00" to ZoneOffset.ofHours(-12),
        ).forEach { (text, offset) ->
            assertEquals(text, offset, ServerClock.read(text, null, now, null)?.offset)
        }
    }

    @Test
    fun `a moment's difference between the two clocks is allowed, and no more`() {
        val now = Instant.parse("2026-03-25T12:00:00Z")

        assertNotNull(
            "the form was fetched a minute after the JSON",
            ServerClock.read("2026-03-25 12:01:00", null, now, null),
        )
        assertNull("seven minutes is not a time zone", ServerClock.read("2026-03-25 12:07:00", null, now, null))
        assertNull("nor is a day", ServerClock.read("2026-03-27 12:00:00", null, now, null))
    }

    @Test
    fun `text that is not a time as Nagios writes one is not read at all`() {
        val now = Instant.parse("2026-03-25T12:00:00Z")
        listOf(
            "",
            "now",
            "25/03/2026 12:00:00",
            "2026-03-25 12:00",
            "2026-13-45 12:00:00",
            "31-31-2026 12:00:00",
            "2026-03-25 12:00:00 extra",
            "9999999999-03-25 12:00:00",
        ).forEach { text -> assertNull(text, ServerClock.read(text, null, now, null)) }
    }

    @Test
    fun `a time is written back in the server's own format and its own local time`() {
        val threeApril = Instant.parse("2026-04-03T04:05:06Z")
        val plusTen = ZoneOffset.ofHours(10)

        assertEquals("04-03-2026 14:05:06", clock(ServerDateFormat.US, plusTen).format(threeApril))
        assertEquals("03-04-2026 14:05:06", clock(ServerDateFormat.EURO, plusTen).format(threeApril))
        assertEquals("2026-04-03 14:05:06", clock(ServerDateFormat.ISO8601, plusTen).format(threeApril))
        assertEquals("2026-04-03T14:05:06", clock(ServerDateFormat.STRICT_ISO8601, plusTen).format(threeApril))
    }

    @Test
    fun `the offset can move a time onto another day`() {
        val lateEvening = Instant.parse("2026-04-03T20:00:00Z")

        assertEquals("04-04-2026 06:00:00", clock(ServerDateFormat.US, ZoneOffset.ofHours(10)).format(lateEvening))
        assertEquals("04-03-2026 16:00:00", clock(ServerDateFormat.US, ZoneOffset.ofHours(-4)).format(lateEvening))
    }

    @Test
    fun `with the order unknown, a date is only written if it reads the same either way`() {
        val unknown = clock(null, ZoneOffset.UTC)

        assertEquals("10-10-2026 13:00:00", unknown.format(Instant.parse("2026-10-10T13:00:00Z")))
        assertEquals("05-05-2027 00:00:00", unknown.format(Instant.parse("2027-05-05T00:00:00Z")))
        assertNull("the 11th of October would be a guess", unknown.format(Instant.parse("2026-10-11T13:00:00Z")))
        assertNull(unknown.format(Instant.parse("2026-04-03T04:05:06Z")))
    }

    @Test
    fun `what is written can be read back to the same moment`() {
        val moment = Instant.parse("2026-12-11T23:59:59Z")
        ServerDateFormat.entries.forEach { format ->
            listOf(ZoneOffset.UTC, ZoneOffset.ofHours(10), ZoneOffset.ofHoursMinutes(-3, -30)).forEach { offset ->
                val text = checkNotNull(clock(format, offset).format(moment))

                val again = ServerClock.read(text, null, moment, format)

                assertEquals("$format $offset $text", offset, again?.offset)
                assertEquals("$format $offset $text", text, again?.format(moment))
            }
        }
    }

    private fun clock(format: ServerDateFormat?, offset: ZoneOffset) = ServerClock(format, offset, nowText = "")

    private fun read(fixture: String, serverNow: Instant): ServerClock? {
        val html = CommandFixtures.page(fixture)
        return ServerClock.read(
            checkNotNull(CommandPage.field(html, "start_time")),
            CommandPage.field(html, "end_time"),
            serverNow,
            remembered = null,
        )
    }
}
