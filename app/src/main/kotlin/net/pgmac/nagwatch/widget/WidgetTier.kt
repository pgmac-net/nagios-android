// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

/**
 * The four layouts a widget has, chosen by how much room it was given.
 *
 * Android reports a widget's size in dp, not in cells, and launchers differ in how big a cell
 * is. So these are breakpoints: a launcher that reports an odd size gets the nearest layout
 * that fits, not a broken one.
 */
enum class WidgetTier {
    /** About one cell: a colour and a number. */
    BADGE,

    /** One row: a count for each kind of problem. */
    COUNTS,

    /** Two rows or so: the counts and the first few problems. */
    SHORT_LIST,

    /** Three rows and up: the counts and a list that scrolls. */
    FULL_LIST,
    ;

    companion object {
        /** Narrower than this and there is room for one number, not four. */
        const val COUNTS_FROM_WIDTH = 110

        /** Shorter than this is a single row of cells. */
        const val LIST_FROM_HEIGHT = 110

        /** From here there is room for a scrolling list with a line of output under each problem. */
        const val FULL_FROM_HEIGHT = 250

        /** A full list also needs the width for that line of output. */
        const val FULL_FROM_WIDTH = 180

        fun of(widthDp: Float, heightDp: Float): WidgetTier = when {
            widthDp < COUNTS_FROM_WIDTH -> BADGE
            heightDp < LIST_FROM_HEIGHT -> COUNTS
            heightDp >= FULL_FROM_HEIGHT && widthDp >= FULL_FROM_WIDTH -> FULL_LIST
            else -> SHORT_LIST
        }
    }
}
