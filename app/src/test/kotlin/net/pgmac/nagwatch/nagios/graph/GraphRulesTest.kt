// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import net.pgmac.nagwatch.nagios.model.ObjectRef
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether what nagiosgraph sent is a graph, tried on what nagiosgraph really sends. The files in
 * `/graphs` are answers from `showgraph.cgi` on the test Nagios (1.5.2), every one of them HTTP
 * 200 and `image/png`: see that directory's README.
 */
class GraphRulesTest {
    private val inline = GraphSize(360, 120)

    // ---------------------------------------------------------------- the header

    @Test
    fun `a PNG's size is read from its first bytes`() {
        assertEquals(PngHeader(441, 175), PngHeader.read(Captures.bytes("graph_day_360x120.png")))
        assertEquals(PngHeader(781, 295), PngHeader.read(Captures.bytes("graph_year_700x240.png")))
        assertEquals(PngHeader(608, 23), PngHeader.read(Captures.bytes("no_data.png")))
        assertEquals(PngHeader(5, 5), PngHeader.read(Captures.bytes("blank.png")))
    }

    @Test
    fun `no more than the header is needed`() {
        val start = Captures.bytes("graph_day_360x120.png").copyOf(PngHeader.LENGTH)
        assertEquals(PngHeader(441, 175), PngHeader.read(start))
        assertNull("one byte short", PngHeader.read(start.copyOf(PngHeader.LENGTH - 1)))
    }

    @Test
    fun `what is not a PNG has no size`() {
        assertNull(PngHeader.read(ByteArray(0)))
        assertNull(PngHeader.read("<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()))
        assertNull("a JPEG", PngHeader.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(40)))
        assertNull("the signature and nothing after it", PngHeader.read(png(1, 1).copyOf(8) + ByteArray(16)))
        // Each of the eight signature bytes and the eight that name the first chunk matters.
        (0 until 16).forEach { index ->
            val broken = png(441, 175).also { it[index] = (it[index] + 1).toByte() }
            assertNull("byte $index changed", PngHeader.read(broken))
        }
    }

    @Test
    fun `a size a PNG may not have is not a size`() {
        assertNull(PngHeader.read(png(0, 175)))
        assertNull(PngHeader.read(png(441, 0)))
        assertNull("the top bit set", PngHeader.read(png(-1, 175)))
        assertNull(PngHeader.read(png(441, Int.MIN_VALUE)))
        assertEquals("the largest there is", PngHeader(Int.MAX_VALUE, 1), PngHeader.read(png(Int.MAX_VALUE, 1)))
        assertEquals(
            "every byte of a size counts",
            PngHeader(0x01020304, 0x05060708),
            PngHeader.read(png(0x01020304, 0x05060708)),
        )
    }

    // ---------------------------------------------------------------- graph or not

    @Test
    fun `a graph is a graph at the size it was asked for`() {
        assertEquals(PngHeader(441, 175), GraphRules.judge(inline, Captures.bytes("graph_day_360x120.png")))
        assertEquals(
            "three things plotted: a taller legend",
            PngHeader(441, 245),
            GraphRules.judge(inline, Captures.bytes("graph_three_series_360x120.png")),
        )
        assertEquals(PngHeader(781, 295), GraphRules.judge(GraphSize.FULL, Captures.bytes("graph_year_700x240.png")))
    }

    @Test
    fun `nagiosgraph's pictures of an error are not graphs, at any size this app asks for`() {
        val sizes = (200..800 step 20).map(GraphSize::inline).distinct() + GraphSize.FULL
        sizes.forEach { size ->
            listOf("no_data.png", "no_such_host.png", "blank.png").forEach { name ->
                assertNull("$name asked for at ${size.geom}", GraphRules.judge(size, Captures.bytes(name)))
            }
        }
    }

    @Test
    fun `nothing, and what is not a picture, are not graphs`() {
        assertNull(GraphRules.judge(inline, ByteArray(0)))
        assertNull(GraphRules.judge(inline, "Internal Server Error".toByteArray()))
    }

    @Test
    fun `a picture is a graph only if it is at least the plot asked for, and not much more`() {
        fun judged(width: Int, height: Int) = GraphRules.judge(inline, png(width, height))

        assertNotNull("exactly the plot", judged(360, 120))
        assertNull("narrower than the plot", judged(359, 175))
        assertNull("shorter than the plot", judged(441, 119))
        assertNotNull(judged(360 + GraphRules.MAX_EXTRA_WIDTH, 175))
        assertNull("wider than any axis makes it", judged(361 + GraphRules.MAX_EXTRA_WIDTH, 175))
        assertNotNull(judged(441, 120 + GraphRules.MAX_EXTRA_HEIGHT))
        assertNull("taller than any legend makes it", judged(441, 121 + GraphRules.MAX_EXTRA_HEIGHT))
        assertNull("a few bytes claiming to be enormous", judged(30_000, 30_000))
    }

    @Test
    fun `the largest picture accepted is one a phone can hold`() {
        val full = GraphSize.FULL
        val pixels = (full.width + GraphRules.MAX_EXTRA_WIDTH).toLong() * (full.height + GraphRules.MAX_EXTRA_HEIGHT)
        assertEquals(true, pixels * 4 < 16L * 1024 * 1024)
    }

    @Test
    fun `a graph asked for at one size is not accepted as another`() {
        // The viewer's larger plot: the inline picture is too small to be its answer.
        assertNull(GraphRules.judge(GraphSize.FULL, Captures.bytes("graph_day_360x120.png")))
    }

    // ---------------------------------------------------------------- what is asked for

    @Test
    fun `the plot asked for fits the room there is, in steps`() {
        assertEquals("a phone: 411dp less the screen's padding", GraphSize(240, 120), GraphSize.inline(379))
        assertEquals(GraphSize(300, 120), GraphSize.inline(381))
        assertEquals(GraphSize(300, 120), GraphSize.inline(440))
        assertEquals(GraphSize(360, 120), GraphSize.inline(441))
        assertEquals("a tablet", GraphSize(660, 120), GraphSize.inline(2000))
        assertEquals("never smaller than can be read", GraphSize(240, 120), GraphSize.inline(0))
        assertEquals(GraphSize(240, 120), GraphSize.inline(-50))
        assertEquals("few enough sizes to keep", 8, (0..3000).map(GraphSize::inline).distinct().size)
    }

    @Test
    fun `every plot asked for is taller than a few lines of error message`() {
        val shortest = ((0..3000).map(GraphSize::inline) + GraphSize.FULL).minOf { it.height }
        // nagiosgraph's error picture is 8 pixels and 15 for each line of the message.
        assertEquals(true, shortest > 8 + 15 * 7)
    }

    @Test
    fun `only the host, the service, the period and the size are sent`() {
        val request = checkNotNull(GraphRequest.of(ObjectRef("web01", "Disk /"), GraphPeriod.WEEK, inline))

        assertEquals(
            listOf("host" to "web01", "service" to "Disk /", "period" to "week", "geom" to "360x120"),
            request.params(),
        )
        assertEquals(listOf("day", "week", "month", "year"), GraphPeriod.entries.map { it.param })
    }

    @Test
    fun `a host has no graph to ask for and no page to open`() {
        assertNull(GraphRequest.of(ObjectRef("web01"), GraphPeriod.DAY, inline))
        assertNull(GraphRequest.pageUrl(BASE, ObjectRef("web01")))
    }

    @Test
    fun `nagiosgraph's own page for a service is beside its pictures, with the names encoded`() {
        assertEquals(
            "https://nagios.example.org/nagios/cgi-bin/show.cgi?host=web01&service=Disk%20%2F%26more",
            GraphRequest.pageUrl(BASE, ObjectRef("web01", "Disk /&more")),
        )
    }

    @Test
    fun `the page handed to a browser never carries a user or password, or anything but the address`() {
        val typed = "https://someone:hunter2@nagios.example.org/nagios/cgi-bin/?token=abc#frag".toHttpUrl()

        val page = checkNotNull(GraphRequest.pageUrl(typed, ObjectRef("web01", "Load")))

        assertEquals("https://nagios.example.org/nagios/cgi-bin/show.cgi?host=web01&service=Load", page)
        listOf("someone", "hunter2", "token", "abc", "frag", "@").forEach { part ->
            assertEquals("$part is in $page", false, part in page)
        }
    }

    private companion object {
        val BASE = "https://nagios.example.org/nagios/cgi-bin/".toHttpUrl()
    }
}
