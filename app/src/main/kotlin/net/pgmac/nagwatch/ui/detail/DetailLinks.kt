// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.NagiosLink
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.status.ObjectLinks
import net.pgmac.nagwatch.ui.browse.BrowseRow
import net.pgmac.nagwatch.ui.browse.HostStyle
import net.pgmac.nagwatch.ui.browse.ServiceStyle
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.checkNotes
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

@Composable
internal fun HostLink(host: BrowseRow<HostState>, actions: DetailActions) {
    Section(R.string.detail_host) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.openHost(host.hostName) }
                .padding(vertical = 8.dp)
                .testTag(DetailTags.HOST_LINK),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateBadge(HostStyle.label(host.state), HostStyle.color(host.state, NagwatchTheme.stateColors))
            Text(host.hostName, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** A host's services, worst first, each a way in to that service. */
internal fun LazyListScope.hostServices(state: DetailUiState, actions: DetailActions) {
    if (!state.ref.isHost) return
    item {
        SectionTitle(pluralStringResource(R.plurals.detail_services, state.services.size, state.services.size))
    }
    items(state.services, key = { "service:${it.key}" }) { row ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.openService(row.hostName, row.service.orEmpty()) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(DetailTags.service(row.service.orEmpty())),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateBadge(ServiceStyle.label(row.state), ServiceStyle.color(row.state, NagwatchTheme.stateColors))
                Text(
                    text = row.service.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val notes = checkNotes(row.markers, row.handling, row.check)
            if (notes.isNotEmpty()) {
                Text(
                    notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun OpenInNagios(url: String, actions: DetailActions) {
    OutlinedButton(
        onClick = { actions.openInNagios(url) },
        modifier = Modifier.padding(16.dp).testTag(DetailTags.OPEN_IN_NAGIOS),
    ) {
        Text(stringResource(R.string.detail_open_in_nagios))
    }
}

/**
 * What the definition says to show beside it: its notes, and the notes and action addresses as
 * buttons to the browser. An address the app will not open is shown as words (NagiosLinks).
 */
@Composable
internal fun Links(links: ObjectLinks, actions: DetailActions) {
    Section(R.string.detail_links, Modifier.testTag(DetailTags.LINKS)) {
        if (links.notes.isNotBlank()) Text(links.notes, style = MaterialTheme.typography.bodyMedium)
        links.notesUrl?.let { LinkButton(it, R.string.detail_link_notes, DetailTags.NOTES_URL, actions) }
        links.actionUrl?.let { LinkButton(it, R.string.detail_link_action, DetailTags.ACTION_URL, actions) }
    }
}

@Composable
internal fun LinkButton(link: NagiosLink, label: Int, tag: String, actions: DetailActions) {
    when (link) {
        is NagiosLink.Open -> OutlinedButton(
            onClick = { actions.openInNagios(link.url) },
            modifier = Modifier.testTag(tag),
        ) { Text(stringResource(label)) }

        is NagiosLink.Text -> Text(
            text = stringResource(R.string.detail_link_not_opened, stringResource(label), link.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
    }
}
