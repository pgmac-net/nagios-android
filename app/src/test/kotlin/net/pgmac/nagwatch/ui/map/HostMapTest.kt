// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.map

import kotlin.math.atan2
import kotlin.math.hypot
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.ui.graph.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The radial map's layout and its tap test (pgmac-net/nagios-android#95). */
class HostMapTest {
    //  router ── switch-a ── web01
    //         └─ switch-b ──┘      (web01 has two parents)
    //  lone
    private val hosts = listOf("router", "switch-a", "switch-b", "web01", "lone")
    private val configs = listOf(
        HostConfig("switch-a", listOf("router")),
        HostConfig("switch-b", listOf("router")),
        HostConfig("web01", listOf("switch-a", "switch-b")),
    )

    @Test
    fun `every host is placed once, on the ring of its depth`() {
        val layout = HostMap.layout(hosts, configs)

        assertEquals(hosts, layout.nodes.map { it.name })
        assertEquals(
            mapOf("router" to 1, "switch-a" to 2, "switch-b" to 2, "web01" to 3, "lone" to 1),
            layout.nodes.associate { it.name to it.ring },
        )
        layout.nodes.forEach { node ->
            assertEquals("${node.name} at its ring's radius", node.ring / 3f, hypot(node.x, node.y), 1e-4f)
        }
    }

    @Test
    fun `every parent gets a line, and a root a line to the middle`() {
        val edges = HostMap.layout(hosts, configs).edges.toSet()

        assertEquals(
            setOf(
                MapEdge(null, "router"),
                MapEdge(null, "lone"),
                MapEdge("router", "switch-a"),
                MapEdge("router", "switch-b"),
                MapEdge("switch-a", "web01"),
                MapEdge("switch-b", "web01"),
            ),
            edges,
        )
    }

    @Test
    fun `a host sits in its first parent's slice, and a bigger branch gets a bigger slice`() {
        val nodes = HostMap.layout(hosts, configs).nodes.associateBy { it.name }
        fun angle(name: String) = nodes.getValue(name).let { atan2(it.y, it.x) }

        // web01 hangs under switch-a, its first parent: on the same bearing, one ring out.
        assertEquals(angle("switch-a"), angle("web01"), 1e-4f)
        // router's branch ends in two hosts and lone in one: router has two thirds of the circle.
        val gap =
            Math.floorMod(
                (angle("lone") - angle("router")).toDouble().times(1e4).toLong(),
                (2 * Math.PI * 1e4).toLong(),
            ) /
                1e4
        assertEquals("their middles are half the circle apart", Math.PI, gap, 1e-3)
    }

    @Test
    fun `a loop of parents and an unknown parent lose nobody and go round no further`() {
        val looped = listOf(HostConfig("a", listOf("b")), HostConfig("b", listOf("a")), HostConfig("c", listOf("gone")))

        val layout = HostMap.layout(listOf("a", "b", "c"), looped)

        assertEquals(listOf("a", "b", "c"), layout.nodes.map { it.name })
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 1), layout.nodes.associate { it.name to it.ring })
        assertTrue(MapEdge(null, "c") in layout.edges)
    }

    @Test
    fun `the same hosts give the same map`() {
        assertEquals(HostMap.layout(hosts, configs), HostMap.layout(hosts, configs))
    }

    @Test
    fun `past the limit nothing is laid out`() {
        val many = (0..HostMap.MAX_HOSTS).map { "h$it" }

        val layout = HostMap.layout(many, emptyList())

        assertTrue(layout.tooLarge)
        assertTrue(layout.nodes.isEmpty())
        assertEquals(HostMap.MAX_HOSTS, HostMap.layout(many.drop(1), emptyList()).nodes.size)
    }

    @Test
    fun `hosts that each name every other as a parent are too many lines to draw`() {
        val names = (0 until 100).map { "h$it" }
        val tangled = names.map { name -> HostConfig(name, names - name) }

        assertTrue(HostMap.layout(names, tangled).tooLarge)
    }

    @Test
    fun `a tap opens the nearest host within reach, and nothing beyond it`() {
        val nodes = listOf(MapNode("a", 0f, 0f, 1), MapNode("b", 10f, 0f, 1))
        val at: (MapNode) -> Pair<Float, Float> = { it.x to it.y }

        assertEquals("a", HostMap.hit(nodes, 3f, 0f, reach = 5f, at = at)?.name)
        assertEquals("b", HostMap.hit(nodes, 7f, 0f, reach = 5f, at = at)?.name)
        assertNull(HostMap.hit(nodes, 5f, 20f, reach = 5f, at = at))
    }

    @Test
    fun `where a host is drawn follows the zoom, so a tap finds it zoomed too`() {
        val node = MapNode("a", 1f, 0f, 1)

        assertEquals(Pair(90f, 50f), mapPoint(node, 100f, 100f, Zoom()))
        val zoomed = Zoom(scale = 2f, offsetX = -10f, offsetY = 5f, max = 12f)
        assertEquals(Pair(120f, 55f), mapPoint(node, 100f, 100f, zoomed))
    }
}
