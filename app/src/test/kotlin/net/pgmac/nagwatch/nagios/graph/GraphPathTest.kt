// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The graph path is a path on the profile's own server, and nothing that could be anything else. */
class GraphPathTest {
    private val base = "https://nagios.example.org:8443/nagios?x=1#top".toHttpUrl()

    @Test
    fun `blank means where Nagios' own CGIs are`() {
        assertEquals("", GraphPath.normalise(""))
        assertEquals("", GraphPath.normalise("   "))
        assertTrue(GraphPath.isValid(""))
        assertNull("so there is nothing to resolve", GraphPath.resolve(base, ""))
    }

    @Test
    fun `a path is stored with a slash at each end`() {
        assertEquals("/nagiosgraph/cgi-bin/", GraphPath.normalise("/nagiosgraph/cgi-bin/"))
        assertEquals("/nagiosgraph/cgi-bin/", GraphPath.normalise("/nagiosgraph/cgi-bin"))
        assertEquals("/nagiosgraph/cgi-bin/", GraphPath.normalise("  /nagiosgraph/cgi-bin/  "))
        assertEquals("/cgi-bin/", GraphPath.normalise("/cgi-bin"))
        assertEquals("/a.b/c_d/e-f/~g/", GraphPath.normalise("/a.b/c_d/e-f/~g"))
    }

    @Test
    fun `it is on the profile's own scheme, host and port, whatever else the profile's URL has`() {
        val resolved = checkNotNull(GraphPath.resolve(base, "/nagiosgraph/cgi-bin"))

        assertEquals("https://nagios.example.org:8443/nagiosgraph/cgi-bin/", resolved.toString())
        assertEquals(
            "a CGI's name is added after it",
            "showgraph.cgi",
            resolved.newBuilder().addPathSegment("showgraph.cgi").build().pathSegments.last(),
        )
    }

    @Test
    fun `anything that could name another server is refused`() {
        listOf(
            "https://elsewhere.example/cgi-bin/",
            "http://elsewhere.example/cgi-bin/",
            "//elsewhere.example/cgi-bin/",
            "///elsewhere.example/",
            "elsewhere.example/cgi-bin/",
            "/cgi-bin/@elsewhere.example/",
            "/\\elsewhere.example/",
            "\\\\elsewhere.example\\cgi-bin",
            "/cgi-bin/:8080/",
        ).forEach { text ->
            assertFalse(text, GraphPath.isValid(text))
            assertNull(text, GraphPath.resolve(base, text))
        }
    }

    @Test
    fun `anything that is more than a plain path is refused`() {
        listOf(
            "nagiosgraph/cgi-bin",
            "/cgi-bin/../admin/",
            "/cgi-bin/./",
            "/..",
            "/cgi-bin//x/",
            "/cgi-bin/?host=x",
            "/cgi-bin/#top",
            "/cgi bin/",
            "/cgi-bin/%2e%2e/",
            "/cgi-bin/%2F/",
            "/cgi-bin/\n/",
            "/cgi-bin/\u0000",
            "/café/",
            "/",
            "/" + "a".repeat(GraphPath.MAX_LENGTH),
        ).forEach { text -> assertFalse(text, GraphPath.isValid(text)) }
    }

    @Test
    fun `the length that counts is the length of what is stored, so that what was accepted stays accepted`() {
        val longest = "/" + "a".repeat(GraphPath.MAX_LENGTH - 2) + "/"
        assertEquals(longest, GraphPath.normalise(longest))
        assertEquals("the same without its last slash", longest, GraphPath.normalise(longest.dropLast(1)))
        // One longer was accepted as typed and refused as stored, until the fuzzer said so.
        assertNull(GraphPath.normalise("/" + "a".repeat(GraphPath.MAX_LENGTH - 1)))
        assertNull(GraphPath.normalise("/" + "a".repeat(GraphPath.MAX_LENGTH - 1) + "/"))
    }

    @Test
    fun `whatever is accepted is accepted again as it was stored`() {
        listOf("/a", "/a/", "  /a/b  ", "/" + "a".repeat(GraphPath.MAX_LENGTH - 2), "/a.b/c_d").forEach { typed ->
            val stored = checkNotNull(GraphPath.normalise(typed))
            assertEquals(typed, stored, GraphPath.normalise(stored))
        }
    }

    @Test
    fun `what was stored comes back the same`() {
        listOf("", "/cgi-bin/", "/nagiosgraph/cgi-bin/").forEach { stored ->
            assertEquals(stored, GraphPath.normalise(stored))
        }
    }
}
