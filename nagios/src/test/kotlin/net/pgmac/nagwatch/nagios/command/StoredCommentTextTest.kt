// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import org.junit.Assert.assertEquals
import org.junit.Test

/** What Nagios keeps of a comment. Checked against a real one by `CommandLiveTest`. */
class StoredCommentTextTest {
    @Test
    fun `ordinary text is kept as it is`() {
        assertEquals("Disk at 97%, swapping /dev/sda", storedCommentText("Disk at 97%, swapping /dev/sda"))
        assertEquals("naïve ✓ \"quoted\" & ampersand", storedCommentText("naïve ✓ \"quoted\" & ampersand"))
    }

    @Test
    fun `angle brackets are dropped`() {
        assertEquals("see bticket/b", storedCommentText("see <b>ticket</b>"))
        assertEquals("a  b", storedCommentText("a <> b"))
    }

    @Test
    fun `a semicolon becomes a space, because it would end the comment in Nagios' command`() {
        assertEquals("first  second", storedCommentText("first; second"))
    }

    @Test
    fun `a line break becomes a space, because it would end the command`() {
        assertEquals("line one line two", storedCommentText("line one\nline two"))
        assertEquals("line one  line two", storedCommentText("line one\r\nline two"))
    }

    @Test
    fun `space at the ends goes, and nothing but space leaves nothing`() {
        assertEquals("x", storedCommentText("  x \n"))
        assertEquals("", storedCommentText(" ;\n<> "))
        assertEquals("", storedCommentText(""))
    }
}
