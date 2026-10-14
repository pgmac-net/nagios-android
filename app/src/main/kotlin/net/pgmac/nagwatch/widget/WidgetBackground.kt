// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** A widget's background at full strength, as every widget was before it could be anything else. */
const val OPAQUE = 100

/**
 * How a widget's background is drawn for the opacity its owner chose (pgmac-net/nagios-android#65).
 *
 * Every layout takes its background from here, so the rules are in one place and can be tested
 * without drawing a widget. Colours come in already resolved for light or dark; this decides only
 * how they are mixed and how much of the wallpaper shows through.
 */
object WidgetBackground {
    /** How much of the worst state's colour is washed into the surface: enough to tell red from green. */
    const val WASH = 0.15f

    /**
     * An old or unconfirmed widget is never fainter than this. Its grey is one of the ways it says
     * "this is not current", and a background turned down to nothing would take that with it.
     */
    const val STALE_FLOOR = 60

    /**
     * Below this the badge's colour is too faint to carry a white number, so the number takes the
     * state's colour instead: a faint red badge still says red.
     */
    const val BADGE_WHITE_FROM = 40

    /** The opacity actually drawn, 0 to 100. */
    fun opacity(chosen: Int, demoted: Boolean): Int {
        val clamped = chosen.coerceIn(0, OPAQUE)
        return if (demoted) maxOf(clamped, STALE_FLOOR) else clamped
    }

    /** The surface behind counts and lists: the theme's own, washed with the state, at the drawn opacity. */
    fun surface(base: Color, state: Color, chosen: Int, demoted: Boolean): Color =
        lerp(base.copy(alpha = 1f), state.copy(alpha = 1f), WASH).copy(alpha = opacity(chosen, demoted) / 100f)

    /** The 1x1 badge's fill: the state's own colour at the drawn opacity. */
    fun badge(state: Color, chosen: Int, demoted: Boolean): Color =
        state.copy(alpha = state.alpha * opacity(chosen, demoted) / 100f)

    /** Whether the badge's number or tick can stay the colour drawn on a full badge. */
    fun badgeKeepsItsInk(chosen: Int, demoted: Boolean): Boolean = opacity(chosen, demoted) >= BADGE_WHITE_FROM
}
