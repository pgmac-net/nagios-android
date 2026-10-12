// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

/** Names for the parts of the widget, so that tests can find them. */
object WidgetTags {
    const val ROOT = "widget_root"
    const val BADGE = "widget_badge"
    const val COUNTS = "widget_counts"
    const val SHORT_LIST = "widget_short_list"
    const val FULL_LIST = "widget_full_list"
    const val STATUS = "widget_status"
    const val NUMBER = "widget_number"
    const val REFRESH = "widget_refresh"
    const val MESSAGE = "widget_message"
    const val ALL_CLEAR = "widget_all_clear"

    fun count(kind: String) = "widget_count_$kind"

    fun row(index: Int) = "widget_row_$index"
}
