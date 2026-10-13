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
        assertEquals(
            "in the middle, the picture grows from the middle",
            Zoom(Zoom.DOUBLE_TAP, 0f, 0f),
            fitted.toggled(W, H),
        )
        assertTrue(fitted.toggled(W, H).isZoomed)
        assertEquals(fitted, fitted.toggled(W, H).toggled(W, H))
        assertEquals(fitted, fitted.moved(3f, 0f, 0f, W, H).moved(1f, 200f, 100f, W, H).toggled(W, H, 10f, 10f))
    }

    @Test
    fun `a double tap zooms in on the spot that was tapped, not on the middle`() {
        // Three quarters of the way across, half way down: that spot stays under the finger.
        val zoomed = fitted.toggled(W, H, focusX = W * 0.75f, focusY = H / 2)

        assertEquals(Zoom(2f, -W / 4, 0f), zoomed)
        assertEquals(
            "the same part of the picture is under the finger",
            under(fitted, W * 0.75f, H / 2),
            under(
                zoomed,
                W * 0.75f,
                H / 2,
            ),
        )
    }

    @Test
    fun `a double tap in a corner brings that corner of the picture into the corner of the frame`() {
        assertEquals(Zoom(2f, W / 2, H / 2), fitted.toggled(W, H, 0f, 0f))
        assertEquals(Zoom(2f, -W / 2, -H / 2), fitted.toggled(W, H, W, H))
    }

    @Test
    fun `a pinch grows the picture about the fingers, and keeps doing so as it goes on`() {
        val focus = W * 0.2f to H * 0.7f
        val before = under(fitted, focus.first, focus.second)

        val once = fitted.moved(1.5f, 0f, 0f, W, H, focus.first, focus.second)
        val twice = once.moved(1.5f, 0f, 0f, W, H, focus.first, focus.second)

        assertNear(before, under(once, focus.first, focus.second))
        assertNear(before, under(twice, focus.first, focus.second))
        assertEquals(2.25f, twice.scale, 0.001f)
    }

    @Test
    fun `at the limit a pinch moves nothing, because the picture did not grow`() {
        val atMost = fitted.moved(Zoom.MAX, 0f, 0f, W, H)

        assertEquals(atMost, atMost.moved(3f, 0f, 0f, W, H, 10f, 10f))
        assertEquals(fitted, fitted.moved(0.2f, 0f, 0f, W, H, 10f, 10f))
    }

    @Test
    fun `fingers outside the picture's reach cannot pull it off its frame`() {
        val pulled = fitted.moved(4f, 0f, 0f, W, H, focusX = -5_000f, focusY = 9_000f)

        assertEquals(Zoom(4f, W * 1.5f, -H * 1.5f), pulled)
    }

    @Test
    fun `a focus that is not a number is the middle`() {
        assertEquals(Zoom(2f, 0f, 0f), fitted.moved(2f, 0f, 0f, W, H, Float.NaN, Float.POSITIVE_INFINITY))
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

    /** Which point of the picture, measured from its middle in its own units, is under a point of the frame. */
    private fun under(zoom: Zoom, x: Float, y: Float): Pair<Float, Float> =
        (x - W / 2 - zoom.offsetX) / zoom.scale to (y - H / 2 - zoom.offsetY) / zoom.scale

    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.01f)
        assertEquals(expected.second, actual.second, 0.01f)
    }

    private companion object {
        const val W = 800f
        const val H = 300f
    }
}
