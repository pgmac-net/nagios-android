// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * The large icon of a notification (pgmac-net/nagios-android#91): a circle in the state's colour
 * with the app's own glyph in it. Drawn when the notification is posted, so it follows the
 * theme's colours, and not shipped as pictures.
 *
 * It is the one signal a shade that draws the app's launcher icon in place of the small icon
 * (a Pixel on Android 17) does not take away.
 */
internal object LargeIcon {
    /** Android scales a large icon to its own size; this is only enough to stay sharp. */
    private const val SIZE_DP = 64

    /** The glyph fills this much of the circle's width. */
    private const val GLYPH_SHARE = 0.56f

    fun render(context: Context, @ColorInt fill: Int, @DrawableRes glyph: Int, @ColorInt glyphColor: Int): Bitmap {
        val size = (SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val drawable = checkNotNull(ContextCompat.getDrawable(context, glyph)) { "no drawable $glyph" }.mutate()
        drawable.setTint(glyphColor)
        val inset = (size * (1 - GLYPH_SHARE) / 2).roundToInt()
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.draw(canvas)
        return bitmap
    }
}
