// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import net.pgmac.nagwatch.ui.theme.StateColors
import net.pgmac.nagwatch.ui.theme.of
import net.pgmac.nagwatch.ui.theme.shortWord
import net.pgmac.nagwatch.ui.widget.WidgetConfigActivity

/**
 * One widget, drawn for the size it has. Every layout is drawn from the same [WidgetView];
 * none of them decides what state the data is in or what order problems come in.
 */
@Composable
fun StatusWidgetContent(view: WidgetView, appWidgetId: Int) {
    val size = LocalSize.current
    val content = view.content
    val context = LocalContext.current
    val profileId = view.profileId
    // With no profile there is nothing to open but the screen that chooses one.
    val open = if (profileId == null) configure(context, appWidgetId) else open(context, NotificationTarget(profileId))
    val wide = size.width.value >= StatusWidget.WIDE
    Box(
        modifier = GlanceModifier.fillMaxSize()
            // Transparent here: each layout draws the background itself, at the widget's opacity.
            // Drawn here as well, the two would stack and a 50% widget would come out at 75%.
            .background(TRANSPARENT)
            .cornerRadius(CORNER.dp)
            .clickable(open)
            .semantics { testTag = WidgetTags.ROOT },
    ) {
        when {
            content.counts == null -> Message(content, view.opacity)

            else -> when (WidgetTier.of(size.width.value, size.height.value)) {
                WidgetTier.BADGE -> Badge(content, view.opacity)
                WidgetTier.COUNTS -> Counts(content, wide, view.opacity)
                WidgetTier.SHORT_LIST -> ShortList(view, wide)
                WidgetTier.FULL_LIST -> FullList(view, wide)
            }
        }
    }
}

/** Nothing is known: say which kind of nothing. Never a zero, which would read as "all is well". */
@Composable
private fun Message(content: WidgetContent, opacity: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface(content, opacity)).padding(PAD.dp)
            .semantics { testTag = WidgetTags.MESSAGE },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content.profileName?.let { Text(it, style = text(SMALL, bold = true), maxLines = 1) }
        Text(statusText(LocalContext.current, content), style = text(SMALL), maxLines = 3)
    }
}

@Composable
private fun Badge(content: WidgetContent, opacity: Int) {
    val total = content.counts?.total ?: 0
    val state = when {
        content.isDemoted -> DEMOTED
        else -> content.worst?.color() ?: OK
    }
    val (day, night) = state.inEach()
    val fill = ColorProvider(
        WidgetBackground.badge(day, opacity, content.isDemoted),
        WidgetBackground.badge(night, opacity, content.isDemoted),
    )
    // A faint badge cannot carry a white number: the number and the icon take the state's colour.
    val ink = if (WidgetBackground.badgeKeepsItsInk(opacity, content.isDemoted)) ON_STATE else state
    Column(
        modifier = GlanceModifier.fillMaxSize().background(fill).semantics { testTag = WidgetTags.BADGE },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            // Old or unconfirmed: the warning sign goes with the number, so the grey is not the only clue.
            content.isDemoted -> Image(
                ImageProvider(R.drawable.ic_tab_problems),
                null,
                GlanceModifier.size(ICON.dp),
                colorFilter = ColorFilter.tint(ink),
            )

            total == 0 -> Image(
                ImageProvider(R.drawable.ic_check),
                null,
                GlanceModifier.size(BIG_ICON.dp),
                colorFilter = ColorFilter.tint(ink),
            )
        }
        if (total > 0 || content.isDemoted) {
            Text(
                text = total.toString(),
                style = TextStyle(color = ink, fontSize = NUMBER.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.semantics { testTag = WidgetTags.NUMBER },
            )
        }
    }
}

@Composable
private fun Counts(content: WidgetContent, wide: Boolean, opacity: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface(content, opacity)).padding(PAD.dp)
            .semantics { testTag = WidgetTags.COUNTS },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chips(content, wide)
        // The time is a nicety when all is current. When it is not, it is the point.
        if (wide || content.isDemoted) StatusLine(content)
    }
}

@Composable
private fun ShortList(view: WidgetView, wide: Boolean) {
    val content = view.content
    val rows = rowsThatFit(LocalSize.current.height.value, chipRows = if (wide) 1 else 2)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface(view.content, view.opacity)).padding(PAD.dp)
            .semantics { testTag = WidgetTags.SHORT_LIST },
    ) {
        Chips(content, wide)
        AllClear(content)
        content.rows.take(rows).forEachIndexed { index, row -> ProblemRow(view, row, index, withOutput = false) }
        Spacer(GlanceModifier.defaultWeight())
        StatusLine(content)
    }
}

@Composable
private fun FullList(view: WidgetView, wide: Boolean) {
    val content = view.content
    val profileId = view.profileId
    Column(
        modifier = GlanceModifier.fillMaxSize().background(surface(view.content, view.opacity)).padding(PAD.dp)
            .semantics { testTag = WidgetTags.FULL_LIST },
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                content.profileName.orEmpty(),
                style = text(BODY, bold = true),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (profileId != null) {
                Text(
                    text = LocalContext.current.getString(R.string.widget_refresh),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = SMALL.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = GlanceModifier.padding(horizontal = PAD.dp)
                        .clickable(
                            actionRunCallback<RefreshAction>(actionParametersOf(RefreshAction.PROFILE to profileId)),
                        )
                        .semantics { testTag = WidgetTags.REFRESH },
                )
            }
        }
        Chips(content, wide)
        AllClear(content)
        LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
            items(content.rows.size) { index -> ProblemRow(view, content.rows[index], index, withOutput = true) }
            if (content.more > 0) {
                item {
                    Text(
                        LocalContext.current.resources.getQuantityString(
                            R.plurals.widget_more,
                            content.more,
                            content.more,
                        ),
                        style = text(SMALL),
                    )
                }
            }
        }
        StatusLine(content)
    }
}

/**
 * An empty list that is known to be empty says so: four zeros over a blank space look like a
 * widget that has not loaded. Only when the data is current. Old or unconfirmed, the status line
 * already says what is known, and "nothing" would be a claim nobody can make.
 */
@Composable
private fun AllClear(content: WidgetContent) {
    if (content.state != WidgetState.CURRENT || content.rows.isNotEmpty()) return
    Text(
        text = LocalContext.current.getString(R.string.widget_all_clear),
        style = text(BODY),
        maxLines = 1,
        modifier = GlanceModifier.padding(vertical = 2.dp).semantics { testTag = WidgetTags.ALL_CLEAR },
    )
}

/**
 * The four counts: in one row where there is the width for it, two by two where there is not.
 * Never a row that runs off the edge. On a phone, four across in a widget two cells wide showed
 * two kinds and hid the other two, which reads as "no warnings" when nobody said so.
 */
@Composable
private fun Chips(content: WidgetContent, across: Boolean) {
    val counts = content.counts ?: return
    val chips = listOf(
        ChipSpec("down", R.string.severity_down, counts.hostsDown, Severity.HOST_DOWN),
        ChipSpec("critical", R.string.severity_critical, counts.critical, Severity.CRITICAL),
        ChipSpec("warning", R.string.severity_warning, counts.warning, Severity.WARNING),
        ChipSpec("unknown", R.string.severity_unknown, counts.unknown, Severity.UNKNOWN),
    )
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        chips.chunked(if (across) chips.size else NARROW_CHIPS_PER_ROW).forEachIndexed { line, row ->
            if (line > 0) Spacer(GlanceModifier.height(GAP.dp))
            // A gap is a spacer: padding is inside a view's background, so it would only make the chip wider.
            Row(
                modifier = GlanceModifier.fillMaxWidth().semantics { testTag = WidgetTags.CHIP_ROW },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEachIndexed { index, chip ->
                    if (index > 0) Spacer(GlanceModifier.width(GAP.dp))
                    Chip(chip, content.isDemoted)
                }
            }
        }
    }
}

private class ChipSpec(val kind: String, val label: Int, val count: Int, val severity: Severity)

@Composable
private fun Chip(chip: ChipSpec, demoted: Boolean) {
    val count = chip.count
    // A kind with nothing in it is drawn plain, so that the eye goes to the ones that have something.
    val active = count > 0 && !demoted
    Text(
        text = "${LocalContext.current.getString(chip.label)} $count",
        style = TextStyle(
            color = if (active) ON_STATE else GlanceTheme.colors.onSurfaceVariant,
            fontSize = SMALL.sp,
            fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = 1,
        modifier = GlanceModifier
            .background(
                if (active) {
                    chip.severity.color()
                } else if (count > 0) {
                    DEMOTED_CHIP
                } else {
                    TRANSPARENT
                },
            )
            .cornerRadius(CHIP_CORNER.dp)
            .padding(horizontal = GAP.dp, vertical = 1.dp)
            .semantics { testTag = WidgetTags.count(chip.kind) },
    )
}

@Composable
private fun ProblemRow(view: WidgetView, row: WidgetRow, index: Int, withOutput: Boolean) {
    val context = LocalContext.current
    val profileId = view.profileId ?: return
    val name = row.ref.description?.let { "${row.ref.hostName} / $it" } ?: row.ref.hostName
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable(open(context, NotificationTarget(profileId, row.ref)))
            .semantics { testTag = WidgetTags.row(index) },
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Old or unconfirmed, the state word loses its colour with everything else.
            val plain = GlanceTheme.colors.onSurfaceVariant
            val wordColor = if (view.content.isDemoted) plain else row.severity.textColor()
            Text(
                text = context.getString(row.severity.shortWord()),
                style = TextStyle(
                    color = wordColor,
                    fontSize = SMALL.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(GAP.dp))
            Text(name, style = text(BODY), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(formatAge(row.since, view.now), style = text(SMALL), maxLines = 1)
        }
        if (withOutput && row.output.isNotEmpty()) Text(row.output, style = text(SMALL), maxLines = 1)
    }
}

@Composable
private fun StatusLine(content: WidgetContent) {
    Text(
        text = statusText(LocalContext.current, content),
        style = TextStyle(
            color = if (content.isDemoted) WARN_TEXT else GlanceTheme.colors.onSurfaceVariant,
            fontSize = SMALL.sp,
            fontWeight = if (content.isDemoted) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = 1,
        modifier = GlanceModifier.semantics { testTag = WidgetTags.STATUS },
    )
}

/**
 * The one line that says how far what is shown can be trusted. Plain, so that it can be tested
 * without drawing anything.
 */
fun statusText(context: Context, content: WidgetContent, zone: ZoneId = ZoneId.systemDefault()): String {
    val asOf = content.asOf?.let { clockTime(it, zone) }
    return when (content.state) {
        WidgetState.CURRENT -> context.getString(R.string.widget_updated, asOf)

        WidgetState.STALE -> context.getString(R.string.widget_stale, asOf)

        WidgetState.UNREACHABLE ->
            if (asOf == null) {
                context.getString(R.string.widget_unreachable_no_data)
            } else {
                context.getString(R.string.widget_unreachable, asOf)
            }

        WidgetState.NO_DATA -> context.getString(R.string.widget_no_data)

        WidgetState.PROFILE_GONE -> context.getString(R.string.widget_no_profile)
    }
}

/**
 * How many problems a short list has room for, at this height. Between two and four; a second row
 * of counts takes about a problem's worth.
 */
fun rowsThatFit(heightDp: Float, chipRows: Int = 1): Int =
    ((heightDp - SHORT_LIST_CHROME - (chipRows - 1) * CHIP_ROW) / SHORT_LIST_ROW)
        .toInt()
        .coerceIn(MIN_SHORT_ROWS, MAX_SHORT_ROWS)

private fun clockTime(instant: Instant, zone: ZoneId): String = TIME.format(instant.atZone(zone))

/** Through the same door a notification's tap uses: nothing else is believed by the app. */
private fun open(context: Context, target: NotificationTarget): Action = actionStartActivity(target.toIntent(context))

private fun configure(context: Context, appWidgetId: Int): Action = actionStartActivity(
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

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
private fun surface(content: WidgetContent, opacity: Int): ColorProvider {
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
private fun ColorProvider.inEach(): Pair<Color, Color> {
    val context = LocalContext.current
    fun at(night: Int): Color {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return getColor(context.createConfigurationContext(config))
    }
    return at(Configuration.UI_MODE_NIGHT_NO) to at(Configuration.UI_MODE_NIGHT_YES)
}

@Composable
private fun text(size: Int, bold: Boolean = false) = TextStyle(
    color = GlanceTheme.colors.onSurface,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

// State colours carry meaning, so they are the app's own and fixed: they follow light and dark
// and nothing else, on every version of Android (design section 8).
private fun Severity.color(): ColorProvider = stateColor { it.of(this) }

/** For a state word on the widget's own background: the same hues, the other way round for contrast. */
private fun Severity.textColor(): ColorProvider =
    if (this == Severity.WARNING) ColorProvider(WARNING_ON_LIGHT, DarkStateColors.warning) else color()

private fun stateColor(pick: (StateColors) -> Color): ColorProvider =
    ColorProvider(pick(LightStateColors), pick(DarkStateColors))

private val OK = stateColor { it.ok }
private val DEMOTED = stateColor { it.pending }
private val DEMOTED_CHIP = ColorProvider(Color(0x33757575), Color(0x33BDBDBD))
private val TRANSPARENT = ColorProvider(Color.Transparent)
private val ON_STATE = ColorProvider(LightStateColors.onState, DarkStateColors.onState)

/** Amber is unreadable as text on a light background; this is the same hue, darker. */
private val WARNING_ON_LIGHT = Color(0xFF9A6700)
private val WARN_TEXT = ColorProvider(Color(0xFFB3261E), Color(0xFFF2B8B5))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val CORNER = 16
private const val CHIP_CORNER = 6
private const val PAD = 8
private const val GAP = 4
private const val ICON = 16
private const val BIG_ICON = 28
private const val NUMBER = 20
private const val BODY = 13
private const val SMALL = 11
private const val SHORT_LIST_CHROME = 56f
private const val SHORT_LIST_ROW = 22f
private const val CHIP_ROW = 20f
private const val NARROW_CHIPS_PER_ROW = 2
private const val MIN_SHORT_ROWS = 2
private const val MAX_SHORT_ROWS = 4
