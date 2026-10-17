// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

object BrowseTags {
    const val SEARCH = "browse_search"
    const val CLEAR = "browse_clear"
    const val LIST = "browse_list"
    const val HANDLED = "browse_handled"
    const val EMPTY = "browse_empty"
    const val EMPTY_HANDLED_HIDDEN = "browse_empty_handled_hidden"
    const val MODES = "browse_modes"
    const val GROUPS_PENDING = "browse_groups_pending"
    const val GROUPS_RETRY = "browse_groups_retry"
    const val GROUPS_NONE = "browse_groups_none"
    const val TREE_TRUNCATED = "browse_tree_truncated"
    const val MAP = "browse_map"

    fun mode(mode: BrowseMode) = "browse_mode_${mode.name}"

    fun group(key: String) = "browse_group_$key"

    fun tree(key: String) = "browse_tree_$key"

    fun treeToggle(key: String) = "browse_tree_toggle_$key"

    fun chip(state: Enum<*>) = "browse_chip_${state.name}"

    fun row(title: String) = "browse_row_$title"
}
