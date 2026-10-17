// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.ui.theme.of
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
internal fun open(context: Context, target: NotificationTarget): Action = actionStartActivity(target.toIntent(context))

private fun configure(context: Context, appWidgetId: Int): Action = actionStartActivity(
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)
