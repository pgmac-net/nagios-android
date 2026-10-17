// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.shortWord

/**
 * The four counts: in one row where there is the width for it, two by two where there is not.
 * Never a row that runs off the edge. On a phone, four across in a widget two cells wide showed
 * two kinds and hid the other two, which reads as "no warnings" when nobody said so.
 */
@Composable
internal fun Chips(content: WidgetContent, across: Boolean) {
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

internal class ChipSpec(val kind: String, val label: Int, val count: Int, val severity: Severity)

@Composable
internal fun Chip(chip: ChipSpec, demoted: Boolean) {
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
internal fun ProblemRow(view: WidgetView, row: WidgetRow, index: Int, withOutput: Boolean) {
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
internal fun StatusLine(content: WidgetContent) {
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
