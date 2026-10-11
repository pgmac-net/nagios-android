// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading `cmd.cgi`'s pages, against pages a real Nagios produced. */
class CommandPageTest {
    @Test
    fun `Nagios' own success page, and only that, is success`() {
        assertEquals(CommandPage.Result.Accepted, CommandPage.result(page("result_accepted")))
    }

    @Test
    fun `each refusal Nagios makes is told apart`() {
        mapOf(
            "result_readonly" to Refusal.READ_ONLY,
            "result_not_authorised" to Refusal.NOT_AUTHORISED,
            "result_commands_disabled_synthetic" to Refusal.COMMANDS_DISABLED,
            "result_could_not_write_synthetic" to Refusal.COULD_NOT_WRITE,
            "result_authentication_disabled_synthetic" to Refusal.AUTHENTICATION_DISABLED,
        ).forEach { (name, reason) ->
            assertEquals(name, CommandPage.Result.Refused(reason), CommandPage.result(page(name)))
        }
    }

    @Test
    fun `when Nagios rejects what was sent, its own reason is kept`() {
        assertEquals(
            CommandPage.Result.Refused(Refusal.INVALID, "Comment was not entered"),
            CommandPage.result(page("result_comment_missing")),
        )
        assertEquals(
            CommandPage.Result.Refused(
                Refusal.INVALID,
                "Start time must be non-zero or bad format has been submitted.",
            ),
            CommandPage.result(page("result_bad_start_time")),
        )
        assertEquals(
            CommandPage.Result.Refused(Refusal.INVALID, "Start or end time not valid"),
            CommandPage.result(page("result_bad_window")),
        )
    }

    @Test
    fun `a page that says neither yes nor no is unrecognised, never success`() {
        listOf(
            "",
            "<html><body>502 Bad Gateway</body></html>",
            "<html><body>Please sign in</body></html>",
            // The form again, as if the commit had not been understood.
            page("form_acknowledge"),
            // A page for a command type cmd.cgi does not offer.
            page("form_unsupported_type"),
            "Your command request was successfully submitted to Nagios for processing.",
            "<DIV CLASS='somethingElse'>Your command request was successfully submitted</DIV>",
        ).forEach { html ->
            assertEquals(html.take(60), CommandPage.Result.Unrecognised, CommandPage.result(html))
        }
    }

    @Test
    fun `success words inside an error are still an error`() {
        val html = "<DIV CLASS='errorMessage'>Comment 'successfully submitted' was not accepted</DIV>"

        val result = CommandPage.result(html)

        assertTrue(result is CommandPage.Result.Refused)
    }

    @Test
    fun `a read-only user is recognised from the form request alone`() {
        assertTrue(CommandPage.isReadOnly(page("form_readonly")))
        assertFalse(CommandPage.isForm(page("form_readonly")))

        assertFalse(CommandPage.isReadOnly(page("form_acknowledge")))
        assertTrue(CommandPage.isForm(page("form_acknowledge")))
        assertTrue(CommandPage.isForm(page("form_remove_acknowledgement")))
    }

    @Test
    fun `a command type cmd_cgi does not offer is not a form`() {
        assertFalse(CommandPage.isForm(page("form_unsupported_type")))
        assertFalse(CommandPage.isReadOnly(page("form_unsupported_type")))
    }

    @Test
    fun `the times a form is pre-filled with are read exactly as written`() {
        mapOf(
            "form_downtime_us" to ("10-10-2026 13:29:32" to "10-10-2026 15:29:32"),
            "form_downtime_euro" to ("10-10-2026 13:31:45" to "10-10-2026 15:31:45"),
            "form_downtime_iso8601" to ("2026-10-10 13:33:46" to "2026-10-10 15:33:46"),
            "form_downtime_strict_iso8601" to ("2026-10-10T13:35:46" to "2026-10-10T15:35:46"),
            "form_downtime_us_crossing_midnight" to ("10-10-2026 23:37:48" to "10-11-2026 01:37:48"),
        ).forEach { (name, times) ->
            assertEquals(name, times.first, CommandPage.field(page(name), "start_time"))
            assertEquals(name, times.second, CommandPage.field(page(name), "end_time"))
        }
        assertEquals("10-10-2026 13:29:32", CommandPage.field(page("form_check_us"), "start_time"))
        assertNull("a check has no end", CommandPage.field(page("form_check_us"), "end_time"))
    }

    @Test
    fun `fields are found whatever the case and quoting of the markup`() {
        assertEquals("a b", CommandPage.field("""<input type="text" name="x" value="a b">""", "x"))
        assertEquals("a b", CommandPage.field("<INPUT TYPE='TEXT' NAME='x' VALUE='a b'>", "x"))
        assertEquals("", CommandPage.field("<INPUT TYPE='checkbox' NAME='x' CHECKED>", "x"))
        assertEquals("1 < 2 & 3", CommandPage.field("<INPUT NAME='x' VALUE='1 &lt; 2 &amp; 3'>", "x"))
        assertNull(CommandPage.field("<INPUT NAME='y' VALUE='1'>", "x"))
        // Not fooled by an attribute whose name merely ends the same way.
        assertNull(CommandPage.field("<INPUT data-name='x' VALUE='1'>", "x"))
        assertEquals("2", CommandPage.field("<INPUT NAME='y' VALUE='1'><INPUT NAME='x' VALUE='2'>", "x"))
    }

    @Test
    fun `markup in Nagios' message is reduced to its words`() {
        val html = "<DIV CLASS='errorMessage'>An   error<BR><BR>\n occurred &amp; <b>stopped</b></DIV>"

        assertEquals(
            CommandPage.Result.Refused(Refusal.INVALID, "An error occurred & stopped"),
            CommandPage.result(html),
        )
    }

    @Test
    fun `an entity is decoded once, not twice`() {
        val html = "<DIV CLASS='errorMessage'>shows &amp;lt; as text</DIV>"

        assertEquals(CommandPage.Result.Refused(Refusal.INVALID, "shows &lt; as text"), CommandPage.result(html))
    }

    @Test
    fun `however much a server puts in its message, only a little is passed on`() {
        val html = "<DIV CLASS='errorMessage'>" + "x".repeat(50_000) + "</DIV>"

        val result = CommandPage.result(html) as CommandPage.Result.Refused

        assertEquals(CommandPage.MAX_DETAIL, result.detail.length)
    }

    @Test
    fun `malformed and enormous pages are read without trouble`() {
        listOf(
            "<".repeat(600_000),
            "<input".repeat(100_000),
            "<DIV CLASS='errorMessage'>" + "<b>".repeat(100_000),
            "<DIV CLASS='errorMessage'",
            "<INPUT NAME='x' VALUE='unterminated",
            "class='errorMessage'>no div at all",
        ).forEach { html ->
            CommandPage.result(html)
            CommandPage.isReadOnly(html)
            CommandPage.isForm(html)
            CommandPage.field(html, "start_time")
        }
    }

    private fun page(name: String): String = CommandFixtures.page(name)
}

/** The pages in `src/test/resources/commandpages`. */
object CommandFixtures {
    fun page(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("commandpages/$name.html")) { "no fixture $name" }.readText()
}
