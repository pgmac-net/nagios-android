// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.checkNotes
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.quick.QuickActionRow
import net.pgmac.nagwatch.ui.quick.QuickMenu
import net.pgmac.nagwatch.ui.quick.QuickTarget
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

@Composable
internal fun <S : Enum<S>> BrowseRowItem(
    row: BrowseRow<S>,
    style: BrowseStyle<S>,
    now: Instant,
    onOpen: (BrowseRow<S>) -> Unit,
    quick: QuickMenu?,
) {
    // A row that is not a problem can still be rechecked; only a problem can be acknowledged.
    val target = QuickTarget(
        ref = ObjectRef(row.hostName, row.service),
        isProblem = row.handling != null,
        acknowledged = row.check?.acknowledged == true,
    )
    QuickActionRow(target, quick, onOpen = { onOpen(row) }) { gestures ->
        BrowseRowContent(row, style, now, gestures)
    }
}

@Composable
internal fun <S : Enum<S>> BrowseRowContent(
    row: BrowseRow<S>,
    style: BrowseStyle<S>,
    now: Instant,
    modifier: Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(BrowseTags.row(row.title)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateBadge(style.label(row.state), style.color(row.state, NagwatchTheme.stateColors))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // How long it has been in this state. Unknown when only the state was fetched.
            Text(formatAge(row.check?.lastStateChange, now), style = MaterialTheme.typography.labelMedium)
        }
        val output = row.check?.pluginOutput?.lineSequence()?.firstOrNull().orEmpty()
        if (output.isNotEmpty()) {
            Text(output, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val notes = checkNotes(row.markers, row.handling, row.check)
        if (notes.isNotEmpty()) {
            Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
