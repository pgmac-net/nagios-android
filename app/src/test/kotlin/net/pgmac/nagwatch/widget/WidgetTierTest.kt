// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of the four layouts a widget gets, from the size Android reports. */
class WidgetTierTest {
    @Test
    fun `the sizes a Pixel reports for each shape pick the layout the design names`() {
        // Roughly (73n - 16) by (118m - 16) dp for n by m cells.
        assertEquals(WidgetTier.BADGE, WidgetTier.of(57f, 102f))
        assertEquals(WidgetTier.COUNTS, WidgetTier.of(130f, 102f))
        assertEquals(WidgetTier.COUNTS, WidgetTier.of(276f, 102f))
        assertEquals(WidgetTier.SHORT_LIST, WidgetTier.of(130f, 220f))
        assertEquals(WidgetTier.SHORT_LIST, WidgetTier.of(276f, 220f))
        assertEquals(WidgetTier.FULL_LIST, WidgetTier.of(203f, 338f))
        assertEquals(WidgetTier.FULL_LIST, WidgetTier.of(276f, 692f))
    }

    @Test
    fun `each breakpoint belongs to the larger layout`() {
        val w = WidgetTier.COUNTS_FROM_WIDTH.toFloat()
        val h = WidgetTier.LIST_FROM_HEIGHT.toFloat()
        val fullH = WidgetTier.FULL_FROM_HEIGHT.toFloat()
        val fullW = WidgetTier.FULL_FROM_WIDTH.toFloat()

        assertEquals(WidgetTier.BADGE, WidgetTier.of(w - 1, 50f))
        assertEquals(WidgetTier.COUNTS, WidgetTier.of(w, 50f))
        assertEquals(WidgetTier.COUNTS, WidgetTier.of(w, h - 1))
        assertEquals(WidgetTier.SHORT_LIST, WidgetTier.of(w, h))
        assertEquals(WidgetTier.SHORT_LIST, WidgetTier.of(fullW, fullH - 1))
        assertEquals(WidgetTier.FULL_LIST, WidgetTier.of(fullW, fullH))
    }

    @Test
    fun `tall but narrow is a short list, not a full one with no room for its text`() {
        assertEquals(WidgetTier.SHORT_LIST, WidgetTier.of(WidgetTier.FULL_FROM_WIDTH - 1f, 600f))
    }

    @Test
    fun `too narrow for four counts is a badge however tall it is`() {
        assertEquals(WidgetTier.BADGE, WidgetTier.of(60f, 600f))
    }

    @Test
    fun `a size no launcher should report still gets a layout`() {
        assertEquals(WidgetTier.BADGE, WidgetTier.of(0f, 0f))
        assertEquals(WidgetTier.FULL_LIST, WidgetTier.of(5_000f, 5_000f))
    }

    @Test
    fun `a short list has room for between two and four problems`() {
        assertEquals(2, rowsThatFit(WidgetTier.LIST_FROM_HEIGHT.toFloat()))
        assertEquals(2, rowsThatFit(40f))
        assertEquals(3, rowsThatFit(125f))
        assertEquals(4, rowsThatFit(150f))
        assertEquals(4, rowsThatFit(249f))
    }
}
