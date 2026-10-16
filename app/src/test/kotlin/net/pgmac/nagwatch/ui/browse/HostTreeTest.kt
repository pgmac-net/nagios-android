// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.nagios.model.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Parents view's tree (pgmac-net/nagios-android#82). */
class HostTreeTest {
    //  router ── switch-a ── web01
    //         └─ switch-b ──┘          (web01 has two parents)
    //  lone
    private val router = host("router")
    private val switchA = host("switch-a")
    private val switchB = host("switch-b")
    private val web01 = host("web01")
    private val lone = host("lone")
    private val all = listOf(router, switchA, switchB, web01, lone)
    private val configs = listOf(
        HostConfig("router", emptyList()),
        HostConfig("switch-a", listOf("router")),
        HostConfig("switch-b", listOf("router")),
        HostConfig("web01", listOf("switch-a", "switch-b")),
    )

    private val everythingOpen: (String, Boolean) -> Boolean = { _, _ -> true }
    private val asStarted: (String, Boolean) -> Boolean = { _, startsOpen -> startsOpen }

    @Test
    fun `hosts sit under every parent, indented, and roots are hosts with none`() {
        val lines = HostTree.lines(all, all, configs, everythingOpen).lines

        assertEquals(
            listOf("router" to 0, "switch-a" to 1, "web01" to 2, "switch-b" to 1, "web01" to 2, "lone" to 0),
            lines.map { it.row.hostName to it.depth },
        )
        assertEquals("each line of a host is its own", 6, lines.map { it.key }.toSet().size)
        assertEquals(listOf("switch-b"), lines[2].alsoUnder)
        assertEquals(listOf("switch-a"), lines[4].alsoUnder)
    }

    @Test
    fun `a healthy tree starts closed, and a branch with an unhandled problem below starts open`() {
        assertEquals(
            listOf("router", "lone"),
            HostTree.lines(all, all, configs, asStarted).lines.map {
                it.row.hostName
            },
        )

        val down = web01.copy(state = HostState.DOWN, handling = Handling.UNHANDLED)
        val rows = listOf(router, switchA, switchB, down, lone)
        val open = HostTree.lines(rows, rows, configs, asStarted).lines.map { it.row.hostName }

        assertEquals(listOf("router", "switch-a", "web01", "switch-b", "web01", "lone"), open)
    }

    @Test
    fun `a host the filters did not pick is drawn only as the way down to one they did`() {
        val lines = HostTree.lines(listOf(web01), all, configs, everythingOpen).lines

        assertEquals(listOf("router", "switch-a", "web01", "switch-b", "web01"), lines.map { it.row.hostName })
        assertTrue(lines.first().context)
        assertFalse(lines.first { it.row.hostName == "web01" }.context)
    }

    @Test
    fun `a loop of parents does not lose its hosts or go round forever`() {
        val a = host("a")
        val b = host("b")
        val looped = listOf(HostConfig("a", listOf("b")), HostConfig("b", listOf("a")))

        val lines = HostTree.lines(listOf(a, b), listOf(a, b), looped, everythingOpen).lines

        assertEquals(listOf("a", "b"), lines.map { it.row.hostName })
        assertEquals(listOf(0, 1), lines.map { it.depth })
    }

    @Test
    fun `a parent the status does not list is ignored, and a host with no configuration is a root`() {
        val configs = listOf(HostConfig("web01", listOf("gone")))

        val lines = HostTree.lines(listOf(web01, lone), listOf(web01, lone), configs, everythingOpen).lines

        assertEquals(listOf("web01", "lone"), lines.map { it.row.hostName })
        assertTrue(lines.all { it.depth == 0 && !it.hasChildren && it.alsoUnder.isEmpty() })
    }

    @Test
    fun `parents that cross again and again are cut short, not drawn by the million`() {
        // Twenty levels of two hosts, each under both of the level above: 2^20 ways down.
        val levels = (0 until 20).map { level -> listOf(host("a$level"), host("b$level")) }
        val hosts = levels.flatten()
        val configs = levels.flatMapIndexed { level, pair ->
            val above = if (level == 0) emptyList() else levels[level - 1].map { it.hostName }
            pair.map { HostConfig(it.hostName, above) }
        }

        val tree = HostTree.lines(hosts, hosts, configs, everythingOpen)

        assertTrue(tree.truncated)
        assertEquals(HostTree.MAX_LINES, tree.lines.size)
    }

    @Test
    fun `a chain of parents as long as the host list does not run out of stack`() {
        val chain = (0 until 50_000).map { host("h$it") }
        val configs = chain.mapIndexed { i, row ->
            HostConfig(
                row.hostName,
                if (i ==
                    0
                ) {
                    emptyList()
                } else {
                    listOf("h${i - 1}")
                },
            )
        }

        val tree = HostTree.lines(listOf(chain.last()), chain, configs, everythingOpen)

        assertTrue("cut short at the line limit, without a stack overflow", tree.truncated)
    }

    @Test
    fun `no two lines share a key, whatever the names hold`() {
        val slashed = host("a/b")
        val a = host("a")
        val b = host("b")
        val configs = listOf(HostConfig("b", listOf("a")))

        val keys = HostTree.lines(listOf(slashed, a, b), listOf(slashed, a, b), configs, everythingOpen).lines.map {
            it.key
        }

        assertEquals(keys.size, keys.toSet().size)
    }

    private fun host(name: String) = BrowseRow(name, null, HostState.UP, null, null, emptySet())
}
