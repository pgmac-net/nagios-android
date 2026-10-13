// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far a graph can be magnified and dragged, without a finger. */
class ZoomTest {
    private val fitted = Zoom()

    @Test
    fun `a picture starts fitted to its frame, and fitted it cannot be dragged`() {
        assertEquals(Zoom(1f, 0f, 0f), fitted)
        assertFalse(fitted.isZoomed)
        assertEquals(fitted, fitted.moved(1f, 300f, -200f, W, H))
    }

    @Test
    fun `a pinch magnifies, up to four times and no further`() {
        assertEquals(2f, fitted.moved(2f, 0f, 0f, W, H).scale, 0f)
        assertEquals(Zoom.MAX, fitted.moved(2f, 0f, 0f, W, H).moved(2f, 0f, 0f, W, H).scale, 0f)
        assertEquals(Zoom.MAX, fitted.moved(100f, 0f, 0f, W, H).scale, 0f)
        assertEquals(4f, Zoom.MAX, 0f)
    }

    @Test
    fun `a pinch the other way goes back to fitted and no smaller`() {
        val zoomed = fitted.moved(3f, 0f, 0f, W, H)

        assertEquals(1.5f, zoomed.moved(0.5f, 0f, 0f, W, H).scale, 0f)
        assertEquals(1f, zoomed.moved(0.01f, 0f, 0f, W, H).scale, 0f)
    }

    @Test
    fun `magnified, the picture can be dragged until its edge meets the frame's and no further`() {
        val twice = fitted.moved(2f, 0f, 0f, W, H)

        // At twice the size it overhangs by half a frame on each side.
        assertEquals(100f, twice.moved(1f, 100f, 0f, W, H).offsetX, 0f)
        assertEquals(W / 2, twice.moved(1f, 10_000f, 0f, W, H).offsetX, 0f)
        assertEquals(-W / 2, twice.moved(1f, -10_000f, 0f, W, H).offsetX, 0f)
        assertEquals(H / 2, twice.moved(1f, 0f, 10_000f, W, H).offsetY, 0f)
        assertEquals(-H / 2, twice.moved(1f, 0f, -10_000f, W, H).offsetY, 0f)
        // At four times, by a frame and a half.
        assertEquals(W * 1.5f, fitted.moved(4f, 0f, 0f, W, H).moved(1f, 10_000f, 0f, W, H).offsetX, 0f)
    }

    @Test
    fun `drags add up`() {
        val twice = fitted.moved(2f, 0f, 0f, W, H)

        val dragged = twice.moved(1f, 60f, -40f, W, H).moved(1f, 30f, -10f, W, H)

        assertEquals(90f to -50f, dragged.offsetX to dragged.offsetY)
    }

    @Test
    fun `pinching back in pulls a dragged picture back inside its frame`() {
        val atEdge = fitted.moved(4f, 0f, 0f, W, H).moved(1f, 10_000f, 10_000f, W, H)

        val back = atEdge.moved(0.25f, 0f, 0f, W, H)

        assertEquals(Zoom(1f, 0f, 0f), back)
    }

    @Test
    fun `a double tap magnifies a fitted picture and fits a magnified one, wherever it was dragged`() {
        assertEquals(Zoom(Zoom.DOUBLE_TAP, 0f, 0f), fitted.toggled())
        assertTrue(fitted.toggled().isZoomed)
        assertEquals(fitted, fitted.toggled().toggled())
        assertEquals(fitted, fitted.moved(3f, 0f, 0f, W, H).moved(1f, 200f, 100f, W, H).toggled())
    }

    @Test
    fun `numbers that are not numbers change nothing`() {
        val twice = fitted.moved(2f, 50f, 20f, W, H)

        assertEquals(twice, twice.moved(Float.NaN, Float.NaN, Float.NaN, W, H))
        assertEquals(twice, twice.moved(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, W, H))
        assertEquals(twice, twice.moved(0f, 0f, 0f, W, H))
        assertEquals(twice, twice.moved(-3f, 0f, 0f, W, H))
    }

    @Test
    fun `a frame with no size holds the picture still`() {
        val moved = fitted.moved(2f, 500f, 500f, 0f, Float.NaN)

        assertEquals(Zoom(2f, 0f, 0f), moved)
    }

    private companion object {
        const val W = 800f
        const val H = 300f
    }
}
