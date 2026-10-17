// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.format.DateTimeFormatter
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import net.pgmac.nagwatch.ui.theme.StateColors
import net.pgmac.nagwatch.ui.theme.of

/**
 * The widget's own background, set by every layout on its outermost view, never left to be
 * inherited.
 *
 * Android redraws a widget by applying the new layout to the views it already has, and a
 * property the new layout does not mention keeps the value it had. A widget is drawn as a badge
 * for a moment while it is being placed, and a badge's whole background is the state colour. A
 * layout that set no background of its own stayed that colour: a row of counts that was solid
 * red (found on a phone; nothing in a unit test can see it). The rule that follows: **anything a
 * layout does not set, it inherits from whatever was drawn there before.**
 */
@Composable
internal fun surface(content: WidgetContent, opacity: Int): ColorProvider {
    val (baseDay, baseNight) = GlanceTheme.colors.widgetBackground.inEach()
    // Nothing known is not "all well": a message gets no wash, and so no green.
    val wash = when {
        content.counts == null -> null
        content.isDemoted -> DEMOTED
        else -> content.worst?.color() ?: OK
    }
    val (stateDay, stateNight) = wash?.inEach() ?: (baseDay to baseNight)
    return ColorProvider(
        WidgetBackground.surface(baseDay, stateDay, opacity, content.isDemoted),
        WidgetBackground.surface(baseNight, stateNight, opacity, content.isDemoted),
    )
}

/**
 * This colour as it is drawn in a light theme and in a dark one. The widget's own surface is the
 * system's (Material You on Android 12 and later), so it is read back for each, not assumed.
 */
@Composable
internal fun ColorProvider.inEach(): Pair<Color, Color> {
    val context = LocalContext.current
    fun at(night: Int): Color {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return getColor(context.createConfigurationContext(config))
    }
    return at(Configuration.UI_MODE_NIGHT_NO) to at(Configuration.UI_MODE_NIGHT_YES)
}

@Composable
internal fun text(size: Int, bold: Boolean = false) = TextStyle(
    color = GlanceTheme.colors.onSurface,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

// State colours carry meaning, so they are the app's own and fixed: they follow light and dark
// and nothing else, on every version of Android (design section 8).
internal fun Severity.color(): ColorProvider = stateColor { it.of(this) }

/** For a state word on the widget's own background: the same hues, the other way round for contrast. */
internal fun Severity.textColor(): ColorProvider =
    if (this == Severity.WARNING) ColorProvider(WARNING_ON_LIGHT, DarkStateColors.warning) else color()

internal fun stateColor(pick: (StateColors) -> Color): ColorProvider =
    ColorProvider(pick(LightStateColors), pick(DarkStateColors))

internal val OK = stateColor { it.ok }
internal val DEMOTED = stateColor { it.pending }
internal val DEMOTED_CHIP = ColorProvider(Color(0x33757575), Color(0x33BDBDBD))
internal val TRANSPARENT = ColorProvider(Color.Transparent)
internal val ON_STATE = ColorProvider(LightStateColors.onState, DarkStateColors.onState)

/** Amber is unreadable as text on a light background; this is the same hue, darker. */
internal val WARNING_ON_LIGHT = Color(0xFF9A6700)
internal val WARN_TEXT = ColorProvider(Color(0xFFB3261E), Color(0xFFF2B8B5))

internal val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal const val CORNER = 16
internal const val CHIP_CORNER = 6
internal const val PAD = 8
internal const val GAP = 4
internal const val ICON = 16
internal const val BIG_ICON = 28
internal const val NUMBER = 20
internal const val BODY = 13
internal const val SMALL = 11
internal const val SHORT_LIST_CHROME = 56f
internal const val SHORT_LIST_ROW = 22f
internal const val CHIP_ROW = 20f
internal const val NARROW_CHIPS_PER_ROW = 2
internal const val MIN_SHORT_ROWS = 2
internal const val MAX_SHORT_ROWS = 4
