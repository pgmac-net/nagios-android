// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import net.pgmac.nagwatch.nagios.model.HostConfig

/** One line of the Parents view: a host, how deep it sits, and whether it can be opened. */
data class TreeLine<S>(
    val row: BrowseRow<S>,
    val depth: Int,
    /** The way down to it, so a host under two parents has two lines and two keys. */
    val key: String,
    val hasChildren: Boolean,
    val open: Boolean,
    /** Its other parents than the one it is drawn under, so the tree never hides one. */
    val alsoUnder: List<String>,
    /** Shown only because something below it is: the search or the chips did not pick it. */
    val context: Boolean,
)

/**
 * The Parents view of the Hosts tab (pgmac-net/nagios-android#82): hosts under the hosts Nagios
 * says they depend on.
 *
 * - A host is under **every** parent it has, so the tree is true whichever branch is read.
 * - The roots are hosts with no parent. A parent the status does not list is ignored, and a host
 *   that could only be reached through a loop of parents (Nagios refuses one, but a server need
 *   not be trusted) becomes a root, so nobody is lost.
 * - The search, the chips and the Handled switch pick rows as elsewhere. A host they did not pick
 *   is still drawn when something below it was, as the way down to it, and says so ([context]).
 * - Children are in the order of the rows: worst first.
 * - A branch starts open when something below it needs someone; [toggled] turns that over.
 */
object HostTree {
    /**
     * The most lines drawn. A host is under every parent, so parents that cross and cross again
     * multiply the lines (two parents a level, twenty levels deep, is a million). A real install
     * is far below this; past it the tree is cut short and says so, rather than taking the app down.
     */
    const val MAX_LINES = 2_000

    /**
     * The deepest a host is drawn. Each level is a step of the way down in every key below it,
     * and a frame of the walk; a real install is a handful deep. A host at this depth is drawn
     * but not opened, and the tree says it was cut short.
     */
    const val MAX_DEPTH = 32

    fun <S> lines(
        shown: List<BrowseRow<S>>,
        all: List<BrowseRow<S>>,
        configs: List<HostConfig>,
        isOpen: (key: String, startsOpen: Boolean) -> Boolean,
    ): TreeLines<S> = Builder(shown, all, configs, isOpen).build()

    /**
     * A line's key: the way down to it, each name with its length in front, so that no two ways
     * down can be spelt the same whatever the names hold (a host called `a/b` is not `a` then `b`).
     */
    fun key(path: String, name: String): String = "$path${name.length}:$name"

    private class Builder<S>(
        shown: List<BrowseRow<S>>,
        private val all: List<BrowseRow<S>>,
        configs: List<HostConfig>,
        private val isOpen: (String, Boolean) -> Boolean,
    ) {
        private val rows = all.associateBy { it.hostName }
        private val order = all.withIndex().associate { (index, row) -> row.hostName to index }
        private val picked = shown.mapTo(HashSet()) { it.hostName }
        private val parents: Map<String, List<String>> = configs.associate { config ->
            config.name to config.parents.filter { it in rows && it != config.name }.distinct()
        }
        private val children: Map<String, List<String>> = parents
            .flatMap { (child, of) -> of.map { it to child } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.distinct().sortedBy { order.getValue(it) } }

        /** Picked, or above something picked: the way down to it. */
        private val relevant = upFrom(picked)

        /** An unhandled problem that was picked, or above one. */
        private val needsSomeone = upFrom(
            picked.filterTo(HashSet()) { name -> rows.getValue(name).let { it.handling != null && !it.isHandled } },
        )

        /**
         * Each host's children worth drawing, and whether its branch starts open: worked out once
         * per host, not once per line, since a host can be on many lines and have many children.
         * A child that is already on the way down (a loop) is skipped when it comes up.
         */
        private val below = HashMap<String, List<String>>()
        private val startsOpen = HashMap<String, Boolean>()

        private fun below(name: String): List<String> =
            below.getOrPut(name) { children[name].orEmpty().filter { it in relevant && it != name } }

        private fun startsOpen(name: String): Boolean =
            startsOpen.getOrPut(name) { below(name).any { it in needsSomeone } }

        fun build(): TreeLines<S> {
            val lines = mutableListOf<TreeLine<S>>()
            var truncated = false
            // One frame per open level, each taking its children one at a time: the walk holds
            // the way down and no more, however many children a host has or however deep it goes.
            val path = ArrayDeque<Frame>()
            val roots = roots().iterator()
            while (true) {
                val top = path.lastOrNull()
                val name = when {
                    top != null && top.next.hasNext() -> top.next.next()

                    top != null -> {
                        path.removeLast()
                        continue
                    }

                    roots.hasNext() -> roots.next()

                    else -> break
                }
                if (name !in relevant || path.any { it.name == name }) continue
                if (lines.size == MAX_LINES) return TreeLines(lines, truncated = true)

                val key = key(top?.key.orEmpty(), name)
                val children = below(name)
                val deep = path.size >= MAX_DEPTH - 1
                if (deep && children.isNotEmpty()) truncated = true
                val open = !deep && children.isNotEmpty() && isOpen(key, startsOpen(name))
                lines += TreeLine(
                    row = rows.getValue(name),
                    depth = path.size,
                    key = key,
                    hasChildren = !deep && children.isNotEmpty(),
                    open = open,
                    alsoUnder = parents[name].orEmpty().filter { it != top?.name },
                    context = name !in picked,
                )
                if (open) path.addLast(Frame(name, key, children.iterator()))
            }
            return TreeLines(lines, truncated)
        }

        private class Frame(val name: String, val key: String, val next: Iterator<String>)

        /** Hosts with no parent, then any host no root leads to, each taking those it leads to. */
        private fun roots(): List<String> {
            val names = all.map { it.hostName }.distinct()
            val roots = names.filter { parents[it].isNullOrEmpty() }.toMutableList()
            val reached = HashSet<String>()
            roots.forEach { reach(it, reached) }
            names.forEach { name ->
                if (name !in reached) {
                    roots += name
                    reach(name, reached)
                }
            }
            return roots
        }

        private fun reach(from: String, reached: MutableSet<String>) {
            val stack = ArrayDeque(listOf(from))
            while (stack.isNotEmpty()) {
                val name = stack.removeLast()
                if (reached.add(name)) children[name].orEmpty().forEach(stack::addLast)
            }
        }

        /** [from] and every host above any of them. */
        private fun upFrom(from: Set<String>): Set<String> {
            val found = HashSet<String>()
            val stack = ArrayDeque(from)
            while (stack.isNotEmpty()) {
                val name = stack.removeLast()
                if (found.add(name)) parents[name].orEmpty().forEach(stack::addLast)
            }
            return found
        }
    }
}

/** The lines of the tree, and whether it was cut short at [HostTree.MAX_LINES]. */
data class TreeLines<S>(val lines: List<TreeLine<S>>, val truncated: Boolean)
