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
    fun <S> lines(
        shown: List<BrowseRow<S>>,
        all: List<BrowseRow<S>>,
        configs: List<HostConfig>,
        isOpen: (key: String, startsOpen: Boolean) -> Boolean,
    ): List<TreeLine<S>> = Builder(shown, all, configs, isOpen).build()

    private class Builder<S>(
        shown: List<BrowseRow<S>>,
        all: List<BrowseRow<S>>,
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
        private val relevant = HashMap<String, Boolean>()
        private val needsSomeone = HashMap<String, Boolean>()
        private val lines = mutableListOf<TreeLine<S>>()

        fun build(): List<TreeLine<S>> {
            roots().forEach { visit(it, depth = 0, under = null, path = "", onPath = emptySet()) }
            return lines
        }

        /** Hosts with no parent, then any host no root leads to, each taking those it leads to. */
        private fun roots(): List<String> {
            val names = all().sortedBy { order.getValue(it) }
            val roots = names.filter { parents[it].isNullOrEmpty() }.toMutableList()
            val reached = HashSet<String>()
            roots.forEach { reach(it, reached) }
            names.filterNot { it in reached }.forEach { name ->
                if (name !in reached) {
                    roots += name
                    reach(name, reached)
                }
            }
            return roots
        }

        private fun all(): Collection<String> = rows.keys

        private fun reach(from: String, reached: MutableSet<String>) {
            val stack = ArrayDeque(listOf(from))
            while (stack.isNotEmpty()) {
                val name = stack.removeLast()
                if (reached.add(name)) children[name].orEmpty().forEach(stack::addLast)
            }
        }

        private fun visit(name: String, depth: Int, under: String?, path: String, onPath: Set<String>) {
            if (!isRelevant(name, emptySet())) return
            val here = onPath + name
            val below = children[name].orEmpty().filter { it !in here && isRelevant(it, here) }
            val key = "$path/$name"
            val open = below.isNotEmpty() && isOpen(key, below.any { needs(it, here) })
            lines += TreeLine(
                row = rows.getValue(name),
                depth = depth,
                key = key,
                hasChildren = below.isNotEmpty(),
                open = open,
                alsoUnder = parents[name].orEmpty().filter { it != under },
                context = name !in picked,
            )
            if (open) below.forEach { visit(it, depth + 1, name, key, here) }
        }

        /** Picked, or something below it is. */
        private fun isRelevant(name: String, onPath: Set<String>): Boolean = relevant.getOrPut(name) {
            name in picked ||
                children[name].orEmpty().any { it !in onPath && it != name && isRelevant(it, onPath + name) }
        }

        /** It, or something below it, is picked and has a problem nobody has handled. */
        private fun needs(name: String, onPath: Set<String>): Boolean = needsSomeone.getOrPut(name) {
            val row = rows.getValue(name)
            (name in picked && row.handling != null && !row.isHandled) ||
                children[name].orEmpty().any { it !in onPath && it != name && needs(it, onPath + name) }
        }
    }
}
