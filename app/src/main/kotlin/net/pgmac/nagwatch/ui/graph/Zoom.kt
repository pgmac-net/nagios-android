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
data class Zoom(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val isZoomed: Boolean get() = scale > 1f

    /**
     * After a pinch by [zoomBy] and a drag of [panX], [panY], in a frame of [width] by [height].
     * A number that is not one (a gesture can report them) changes nothing.
     */
    fun moved(zoomBy: Float, panX: Float, panY: Float, width: Float, height: Float): Zoom {
        val factor = if (zoomBy.isFinite() && zoomBy > 0f) zoomBy else 1f
        val next = (scale * factor).coerceIn(1f, MAX)
        val limitX = limit(width, next)
        val limitY = limit(height, next)
        return Zoom(
            scale = next,
            offsetX = within(offsetX + finite(panX), limitX),
            offsetY = within(offsetY + finite(panY), limitY),
        )
    }

    /** A double tap: all the way in if it is not, and back out if it is. */
    fun toggled(): Zoom = if (isZoomed) Zoom() else Zoom(scale = DOUBLE_TAP)

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
