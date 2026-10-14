// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for a widget's background, without a widget. */
class WidgetBackgroundTest {
    private val surface = Color(0xFF1C1B1F)
    private val red = Color(0xFFEF5350)

    @Test
    fun `the chosen opacity is what is drawn, from nothing to everything`() {
        assertEquals(0, WidgetBackground.opacity(0, demoted = false))
        assertEquals(55, WidgetBackground.opacity(55, demoted = false))
        assertEquals(OPAQUE, WidgetBackground.opacity(OPAQUE, demoted = false))
    }

    @Test
    fun `an old or unconfirmed widget never fades below the floor, and is not made more opaque above it`() {
        assertEquals(WidgetBackground.STALE_FLOOR, WidgetBackground.opacity(0, demoted = true))
        assertEquals(WidgetBackground.STALE_FLOOR, WidgetBackground.opacity(30, demoted = true))
        assertEquals(85, WidgetBackground.opacity(85, demoted = true))
    }

    @Test
    fun `the surface is the theme's own with a light wash of the state, at the drawn opacity`() {
        val full = WidgetBackground.surface(surface, red, OPAQUE, demoted = false)
        assertEquals(1f, full.alpha, 0.001f)
        assertTrue("washed towards red", full.red > surface.red)
        assertTrue("but only lightly: still mostly the surface", full.red < (surface.red + red.red) / 2)

        assertEquals(0.4f, WidgetBackground.surface(surface, red, 40, demoted = false).alpha, 1f / 255)
        assertEquals(0f, WidgetBackground.surface(surface, red, 0, demoted = false).alpha, 0.001f)
    }

    @Test
    fun `the badge is the state's colour at the drawn opacity`() {
        val half = WidgetBackground.badge(red, 50, demoted = false)
        assertEquals(red.copy(alpha = 1f).red, half.red, 0.001f)
        // Alpha is kept in eight bits, so "half" is 128 of 255.
        assertEquals(0.5f, half.alpha, 1f / 255)
    }

    @Test
    fun `a faint badge's number takes the state's colour, and a stale one never gets that faint`() {
        assertTrue(WidgetBackground.badgeKeepsItsInk(OPAQUE, demoted = false))
        assertTrue(WidgetBackground.badgeKeepsItsInk(WidgetBackground.BADGE_WHITE_FROM, demoted = false))
        assertFalse(WidgetBackground.badgeKeepsItsInk(WidgetBackground.BADGE_WHITE_FROM - 1, demoted = false))
        assertTrue("held at the stale floor", WidgetBackground.badgeKeepsItsInk(0, demoted = true))
    }
}
