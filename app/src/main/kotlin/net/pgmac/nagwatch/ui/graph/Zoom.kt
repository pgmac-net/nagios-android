// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

/**
 * How far a picture is magnified and how far it has been dragged, in the viewer. Plain numbers,
 * so that the limits can be tested without a finger.
 *
 * The picture fills its frame at a scale of one. Magnified, it is larger than the frame by the
 * same factor, and may be dragged until its edge meets the frame's edge and no further: nobody
 * should be able to lose the picture off the side of the screen.
 */
data class Zoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** How far it may be magnified: a picture blurs, a drawing does not. */
    val max: Float = MAX,
) {
    val isZoomed: Boolean get() = scale > 1f

    /**
     * After a pinch by [zoomBy] and a drag of [panX], [panY], in a frame of [width] by [height].
     *
     * [focusX], [focusY] is where the fingers are, measured from the frame's top left: the part
     * of the picture under them stays under them, as far as the edges allow. Without it the
     * picture grows from its middle, and whatever was being looked at slides away. A number
     * that is not one (a gesture can report them) changes nothing.
     */
    @Suppress("LongParameterList") // A gesture is this many numbers.
    fun moved(
        zoomBy: Float,
        panX: Float,
        panY: Float,
        width: Float,
        height: Float,
        focusX: Float = width / 2f,
        focusY: Float = height / 2f,
    ): Zoom {
        val factor = if (zoomBy.isFinite() && zoomBy > 0f) zoomBy else 1f
        val next = (scale * factor).coerceIn(1f, max)
        // What the picture actually grew by, which is less than was asked for at either limit.
        val grew = next / scale
        return Zoom(
            max = max,
            scale = next,
            offsetX = within(about(focusX, width, offsetX, grew) + finite(panX), limit(width, next)),
            offsetY = within(about(focusY, height, offsetY, grew) + finite(panY), limit(height, next)),
        )
    }

    /**
     * A double tap at [focusX], [focusY]: in on that spot if the picture is fitted, and back
     * out to fitted if it is not.
     */
    fun toggled(width: Float, height: Float, focusX: Float = width / 2f, focusY: Float = height / 2f): Zoom =
        if (isZoomed) Zoom(max = max) else Zoom(max = max).moved(DOUBLE_TAP, 0f, 0f, width, height, focusX, focusY)

    /**
     * Where the picture has to sit, along one axis, for the point under [focus] to stay there
     * while it grows by [grew]. The offset is measured from the frame's middle, so the focus is too.
     */
    private fun about(focus: Float, extent: Float, offset: Float, grew: Float): Float {
        val fromMiddle = if (focus.isFinite() && extent.isFinite()) focus - extent / 2f else 0f
        return fromMiddle - (fromMiddle - offset) * grew
    }

    private fun limit(extent: Float, scale: Float): Float =
        if (extent.isFinite() && extent > 0f) extent * (scale - 1f) / 2f else 0f

    /** Adding zero turns the negative zero a clamp can produce into zero: the two are not equal. */
    private fun within(value: Float, limit: Float): Float = value.coerceIn(-limit, limit) + 0f

    private fun finite(value: Float): Float = if (value.isFinite()) value else 0f

    companion object {
        /** Past this the picture is all blur: it was drawn small, to keep its labels readable. */
        const val MAX = 4f
        const val DOUBLE_TAP = 2f
    }
}
