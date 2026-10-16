// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import net.pgmac.nagwatch.nagios.model.HostConfig

/** A host on the map: where it is, in a circle of radius one about the Nagios server, and on which ring. */
data class MapNode(val name: String, val x: Float, val y: Float, val ring: Int)

/** A line from a parent to a host. A null parent is the Nagios server in the middle, for a root. */
data class MapEdge(val parent: String?, val host: String)

data class HostMapLayout(
    val nodes: List<MapNode>,
    val edges: List<MapEdge>,
    /** More hosts than [HostMap.MAX_HOSTS]: nothing is laid out. */
    val tooLarge: Boolean = false,
)

/**
 * The radial map of hosts by parent (pgmac-net/nagios-android#95), in the manner of Nagios' own
 * "balloon" status map.
 *
 * - The Nagios server is the middle. Hosts with no parent sit on the first ring, and each
 *   generation a ring further out.
 * - Every host is placed **once**, under its first parent the status knows; every other parent
 *   still gets a line, so the map hides no dependency.
 * - A host's slice of the circle is in proportion to the hosts at the ends of its branch, so a
 *   busy branch has room. Children share their parent's slice, in the order given (worst first).
 * - A parent the status does not list is ignored, and a host reachable only through a loop of
 *   parents becomes a root: nobody is lost and nothing goes round forever.
 * - Past [MAX_HOSTS] hosts or [MAX_LINES] lines nothing is laid out: on a phone it would be
 *   unreadable, and what a server sends must not decide how much work the app does.
 */
object HostMap {
    const val MAX_HOSTS = 500

    /**
     * The most lines. Hosts are capped, but every one may list every other as a parent, which is
     * a quarter of a million lines drawn every frame; a real install has about one per host.
     */
    const val MAX_LINES = 2_000

    /** @param hosts every host, in the order children are to be spread (worst first). */
    fun layout(hosts: List<String>, configs: List<HostConfig>): HostMapLayout {
        val names = hosts.distinct()
        if (names.size > MAX_HOSTS) return HostMapLayout(emptyList(), emptyList(), tooLarge = true)
        val known = names.toHashSet()
        val parents = configs.associate { config ->
            config.name to config.parents.filter { it in known && it != config.name }.distinct()
        }
        if (names.sumOf { parents[it]?.size?.coerceAtLeast(1) ?: 1 } > MAX_LINES) {
            return HostMapLayout(emptyList(), emptyList(), tooLarge = true)
        }
        return Builder(names, parents).build()
    }

    /**
     * The host a tap at ([x], [y]) is on: the nearest whose centre is within [reach], or none.
     * [at] says where a host is drawn, in the same units as the tap.
     */
    fun hit(nodes: List<MapNode>, x: Float, y: Float, reach: Float, at: (MapNode) -> Pair<Float, Float>): MapNode? =
        nodes
            .map { node -> node to at(node).let { (nx, ny) -> hypot(nx - x, ny - y) } }
            .filter { (_, distance) -> distance <= reach }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    private class Builder(private val names: List<String>, private val parents: Map<String, List<String>>) {
        private val order = names.withIndex().associate { (index, name) -> name to index }
        private val underFirst: Map<String, List<String>> = names
            .mapNotNull { name -> parents[name]?.firstOrNull()?.let { it to name } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { order.getValue(it) } }
        private val children = HashMap<String, List<String>>()
        private val ring = HashMap<String, Int>()

        fun build(): HostMapLayout {
            val roots = place()
            val weight = weigh()
            val nodes = ArrayList<MapNode>(names.size)
            val rings = (ring.values.maxOrNull() ?: 1).coerceAtLeast(1)
            // Each host is given a slice and sits in its middle; its children share the slice.
            val slices = ArrayDeque<Triple<String, Double, Double>>()
            val total = roots.sumOf { weight.getValue(it) }.toDouble()
            var start = -PI / 2
            roots.forEach { root ->
                val span = 2 * PI * weight.getValue(root) / total
                slices.addLast(Triple(root, start, span))
                start += span
            }
            while (slices.isNotEmpty()) {
                val (name, from, span) = slices.removeFirst()
                val angle = from + span / 2
                val radius = ring.getValue(name).toDouble() / rings
                nodes +=
                    MapNode(name, (radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat(), ring.getValue(name))
                var childStart = from
                val below = children[name].orEmpty()
                val sum = below.sumOf { weight.getValue(it) }.toDouble()
                below.forEach { child ->
                    val share = span * weight.getValue(child) / sum
                    slices.addLast(Triple(child, childStart, share))
                    childStart += share
                }
            }
            val edges = names.flatMap { name ->
                val of = parents[name].orEmpty()
                if (of.isEmpty()) listOf(MapEdge(null, name)) else of.map { MapEdge(it, name) }
            }
            return HostMapLayout(nodes.sortedBy { order.getValue(it.name) }, edges)
        }

        /** Breadth first from the roots, each host once: its ring, and which hosts hang under it. */
        private fun place(): List<String> {
            val roots = mutableListOf<String>()
            val queue = ArrayDeque<String>()
            fun root(name: String) {
                roots += name
                ring[name] = 1
                queue.addLast(name)
                drain(queue)
            }
            names.filter { parents[it].isNullOrEmpty() }.forEach(::root)
            // Whoever no root leads to sits in a loop of parents: the first of them becomes a root.
            names.forEach { if (it !in ring) root(it) }
            return roots
        }

        private fun drain(queue: ArrayDeque<String>) {
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                val below = underFirst[name].orEmpty().filter { it !in ring }
                children[name] = below
                below.forEach { child ->
                    ring[child] = ring.getValue(name) + 1
                    queue.addLast(child)
                }
            }
        }

        /** How many hosts end each branch, counted from the outside in. */
        private fun weigh(): Map<String, Int> {
            val weight = HashMap<String, Int>()
            ring.keys.sortedByDescending { ring.getValue(it) }.forEach { name ->
                weight[name] = children[name].orEmpty().sumOf { weight.getValue(it) }.coerceAtLeast(1)
            }
            return weight
        }
    }
}
