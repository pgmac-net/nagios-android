// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.Fixtures
import net.pgmac.nagwatch.nagios.TEST_JSON
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comments are checked against sanitised captures from a real Nagios Core 4.5.9.
 * Downtimes are checked against a hand-written fixture: no downtime existed on
 * the instance the captures came from. Its fields and their types follow
 * `json_status_downtime_details` in Nagios Core's cgi/statusjson.c.
 */
class AnnotationParserTest {
    private val byHost = Fixtures.json("commentlist_by_host.json")

    @Test
    fun `a host filter returns its services' comments too, so the host gets only its own`() {
        val everything = (byHost["data"] as JsonObject)["commentlist"] as JsonObject
        assertEquals("the capture really is mixed", 12, everything.size)

        val comments = AnnotationParser.comments(byHost, ObjectRef("host12"))

        assertEquals(1, comments.size)
        assertEquals(4862L, comments.single().id)
    }

    @Test
    fun `a service gets only its own comments out of the mixed list`() {
        val comments = AnnotationParser.comments(byHost, ObjectRef("host12", "Memory"))

        assertEquals(listOf(649L), comments.map { it.id })
    }

    @Test
    fun `a comment carries its kind, author, text and time`() {
        val comment = AnnotationParser.comments(
            Fixtures.json("commentlist_by_service.json"),
            ObjectRef("host12", "Memory"),
        ).single()

        assertEquals(Comment.Kind.ACKNOWLEDGEMENT, comment.kind)
        assertEquals("nagiosadmin", comment.author)
        assertEquals("Acknowledged: known issue, being worked on", comment.text)
        assertTrue(comment.persistent)
        assertTrue("entry time is read", comment.enteredAt != null && comment.enteredAt.isAfter(Instant.EPOCH))
        assertNull("it does not expire", comment.expiresAt)
    }

    @Test
    fun `nothing for an object that has no comments, or for another host`() {
        assertTrue(AnnotationParser.comments(byHost, ObjectRef("host12", "No such service")).isEmpty())
        assertTrue(AnnotationParser.comments(byHost, ObjectRef("host99")).isEmpty())
    }

    @Test
    fun `comments come newest first`() {
        val body = json(
            """{"data": {"commentlist": {
               "1": {"comment_id": 1, "host_name": "web01", "entry_type": "user", "entry_time": 1000},
               "3": {"comment_id": 3, "host_name": "web01", "entry_type": "user", "entry_time": 3000},
               "2": {"comment_id": 2, "host_name": "web01", "entry_type": "user", "entry_time": 3000}}}}""",
        )

        assertEquals(listOf(3L, 2L, 1L), AnnotationParser.comments(body, ObjectRef("web01")).map { it.id })
    }

    @Test
    fun `every kind of comment is recognised, and an unknown one is not fatal`() {
        fun kindOf(word: String) = AnnotationParser.comments(
            json(
                """{"data": {"commentlist": {"1": {"comment_id": 1, "host_name": "web01", "entry_type": "$word"}}}}""",
            ),
            ObjectRef("web01"),
        ).single().kind

        assertEquals(Comment.Kind.USER, kindOf("user"))
        assertEquals(Comment.Kind.ACKNOWLEDGEMENT, kindOf("acknowledgement"))
        assertEquals(Comment.Kind.DOWNTIME, kindOf("downtime"))
        assertEquals(Comment.Kind.FLAPPING, kindOf("flapping"))
        assertEquals(Comment.Kind.OTHER, kindOf("something new"))
    }

    @Test
    fun `an expiry time is only used when the comment expires`() {
        val body = json(
            """{"data": {"commentlist": {
               "1": {"comment_id": 1, "host_name": "web01", "expires": true, "expire_time": 5000},
               "2": {"comment_id": 2, "host_name": "web01", "expires": false, "expire_time": 5000}}}}""",
        )

        val byId = AnnotationParser.comments(body, ObjectRef("web01")).associateBy { it.id }

        assertEquals(Instant.ofEpochMilli(5000), byId.getValue(1).expiresAt)
        assertNull(byId.getValue(2).expiresAt)
    }

    @Test
    fun `an empty or malformed comment list yields nothing`() {
        assertTrue(AnnotationParser.comments(json("{}"), ObjectRef("web01")).isEmpty())
        assertTrue(AnnotationParser.comments(json("""{"data": {"commentlist": []}}"""), ObjectRef("web01")).isEmpty())
        assertTrue(
            AnnotationParser.comments(json("""{"data": {"commentlist": {"1": "x"}}}"""), ObjectRef("web01")).isEmpty(),
        )
        assertTrue(
            AnnotationParser.downtimes(Fixtures.json("downtimelist_by_host.json"), ObjectRef("host12")).isEmpty(),
        )
    }

    @Test
    fun `a host gets its own downtime, not its services'`() {
        val downtime = AnnotationParser.downtimes(
            Fixtures.json("downtimelist_synthetic.json"),
            ObjectRef("host12"),
        ).single()

        assertEquals(7L, downtime.id)
        assertEquals("nagiosadmin", downtime.author)
        assertEquals("Planned maintenance", downtime.comment)
        assertTrue(downtime.fixed)
        assertTrue(downtime.inEffect)
        assertEquals(Instant.ofEpochMilli(1_791_539_000_000), downtime.start)
        assertEquals(Instant.ofEpochMilli(1_791_546_200_000), downtime.end)
    }

    @Test
    fun `a service gets its own downtime, flexible ones with their length`() {
        val downtime = AnnotationParser.downtimes(
            Fixtures.json("downtimelist_synthetic.json"),
            ObjectRef("host12", "Memory"),
        ).single()

        assertEquals(8L, downtime.id)
        assertFalse(downtime.fixed)
        assertFalse(downtime.inEffect)
        assertEquals(Duration.ofHours(1), downtime.duration)
    }

    @Test
    fun `a duration is in seconds, unlike the timestamps`() {
        fun durationOf(value: Long) = AnnotationParser.downtimes(
            json("""{"data": {"downtimelist": {"1": {"downtime_id": 1, "host_name": "web01", "duration": $value}}}}"""),
            ObjectRef("web01"),
        ).single().duration

        assertEquals(Duration.ofHours(2), durationOf(7_200))
        assertEquals(Duration.ofDays(30), durationOf(2_592_000))
        assertNull("a fixed downtime has none", durationOf(0))
    }

    @Test
    fun `downtimes come soonest first`() {
        val body = json(
            """{"data": {"downtimelist": {
               "5": {"downtime_id": 5, "host_name": "web01", "start_time": 9000},
               "4": {"downtime_id": 4, "host_name": "web01", "start_time": 2000}}}}""",
        )

        assertEquals(listOf(4L, 5L), AnnotationParser.downtimes(body, ObjectRef("web01")).map { it.id })
    }

    @Test
    fun `a single service or host record parses like a list entry`() {
        val service = checkNotNull(StatusParser.service(Fixtures.json("service_detail.json")))
        val host = checkNotNull(StatusParser.host(Fixtures.json("host_detail.json")))

        assertEquals("host12" to "Memory", service.hostName to service.description)
        assertEquals(ServiceState.OK, service.state)
        assertEquals("OK - check passed", service.check.pluginOutput)
        assertEquals("value=42;80;90;0;100", service.check.perfData)
        assertTrue(service.check.activeCheck)
        assertFalse(service.check.flapping)
        assertTrue("next check is read", service.check.nextCheck != null)
        assertEquals("host12", host.name)
        assertTrue(host.check.detailsAvailable)
    }

    @Test
    fun `a response with no record yields null rather than an empty object`() {
        assertNull(StatusParser.service(json("""{"data": {}}""")))
        assertNull(StatusParser.host(json("{}")))
    }

    @Test
    fun `a passive, flapping check with long output is read`() {
        val body = json(
            """{"data": {"service": {"host_name": "web01", "description": "Trap", "status": "warning",
               "check_type": "passive", "is_flapping": true, "long_plugin_output": "more\nlines"}}}""",
        )

        val check = checkNotNull(StatusParser.service(body)).check

        assertFalse(check.activeCheck)
        assertTrue(check.flapping)
        assertEquals("more\nlines", check.longOutput)
    }

    private fun json(text: String) = TEST_JSON.parseToJsonElement(text) as JsonObject
}
