// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.StatusBanners
import net.pgmac.nagwatch.ui.home.UpdatedLabel
import net.pgmac.nagwatch.ui.home.checkNotes
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.theme.StateColors

object BrowseTags {
    const val SEARCH = "browse_search"
    const val CLEAR = "browse_clear"
    const val LIST = "browse_list"
    const val HANDLED = "browse_handled"
    const val EMPTY = "browse_empty"

    fun chip(state: Enum<*>) = "browse_chip_${state.name}"

    fun row(title: String) = "browse_row_$title"
}

/** How one kind of object is drawn: its states in chip order, and the words and colour for each. */
internal class BrowseStyle<S : Enum<S>>(
    val states: List<S>,
    /** States with a chip only while something is in them, to keep the row of chips short. */
    val rareStates: Set<S>,
    @param:StringRes val searchHint: Int,
    @param:StringRes val emptyText: Int,
    val label: (S) -> Int,
    val color: (S, StateColors) -> Color,
)

internal val HostStyle = BrowseStyle(
    states = listOf(HostState.UP, HostState.DOWN, HostState.UNREACHABLE, HostState.PENDING),
    rareStates = setOf(HostState.PENDING),
    searchHint = R.string.browse_search_hosts,
    emptyText = R.string.browse_empty_hosts,
    label = { state ->
        when (state) {
            HostState.UP -> R.string.state_up
            HostState.DOWN -> R.string.severity_down
            HostState.UNREACHABLE -> R.string.state_unreachable_short
            HostState.PENDING -> R.string.state_pending
        }
    },
    color = { state, colors ->
        when (state) {
            HostState.UP -> colors.ok
            HostState.DOWN -> colors.critical
            HostState.UNREACHABLE -> colors.unknown
            HostState.PENDING -> colors.pending
        }
    },
)

internal val ServiceStyle = BrowseStyle(
    states = listOf(
        ServiceState.OK,
        ServiceState.WARNING,
        ServiceState.CRITICAL,
        ServiceState.UNKNOWN,
        ServiceState.PENDING,
    ),
    rareStates = setOf(ServiceState.PENDING),
    searchHint = R.string.browse_search_services,
    emptyText = R.string.browse_empty_services,
    label = { state ->
        when (state) {
            ServiceState.OK -> R.string.state_ok
            ServiceState.WARNING -> R.string.severity_warning
            ServiceState.CRITICAL -> R.string.severity_critical
            ServiceState.UNKNOWN -> R.string.severity_unknown
            ServiceState.PENDING -> R.string.state_pending
        }
    },
    color = { state, colors ->
        when (state) {
            ServiceState.OK -> colors.ok
            ServiceState.WARNING -> colors.warning
            ServiceState.CRITICAL -> colors.critical
            ServiceState.UNKNOWN -> colors.unknown
            ServiceState.PENDING -> colors.pending
        }
    },
)

@Composable
internal fun HostsTab(status: ProfileStatus, now: Instant, onOpen: (hostName: String) -> Unit) {
    val snapshot = status.snapshot
    val report = status.report
    val rows = remember(snapshot, report) {
        if (snapshot == null || report == null) emptyList() else BrowseRows.hosts(snapshot, report)
    }
    BrowseList(rows, HostStyle, status, now, onOpen = { onOpen(it.hostName) })
}

@Composable
internal fun ServicesTab(status: ProfileStatus, now: Instant, onOpen: (hostName: String, description: String) -> Unit) {
    val snapshot = status.snapshot
    val report = status.report
    val rows = remember(snapshot, report) {
        if (snapshot == null || report == null) emptyList() else BrowseRows.services(snapshot, report)
    }
    BrowseList(rows, ServiceStyle, status, now, onOpen = { onOpen(it.hostName, it.service.orEmpty()) })
}

/**
 * A searchable list of hosts or of services. The search box stays put; the
 * chips, the time of the data and any banners scroll away with the rows.
 */
@Composable
internal fun <S : Enum<S>> BrowseList(
    rows: List<BrowseRow<S>>,
    style: BrowseStyle<S>,
    status: ProfileStatus,
    now: Instant,
    onOpen: (BrowseRow<S>) -> Unit,
) {
    // Kept here rather than in the view model: what is typed must reach the text field in the
    // same frame, and the tab's saved state already outlives switching to another tab.
    var query by rememberSaveable { mutableStateOf("") }
    var states by rememberSaveable { mutableStateOf(emptySet<S>()) }
    var showHandled by rememberSaveable { mutableStateOf(true) }
    val view = remember(rows, query, states, showHandled) { BrowseFilter(query, states, showHandled).apply(rows) }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query, style.searchHint, onChange = { query = it })
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(BrowseTags.LIST)) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChips(
                        style = style,
                        view = view,
                        selected = states,
                        showHandled = showHandled,
                        onToggleState = { states = if (it in states) states - it else states + it },
                        onToggleHandled = { showHandled = !showHandled },
                    )
                    status.lastSuccess?.let { UpdatedLabel(it) }
                }
            }
            item { StatusBanners(status, now) }
            if (view.rows.isEmpty()) {
                item { Empty(if (rows.isEmpty()) style.emptyText else R.string.browse_none) }
            } else {
                items(view.rows, key = { it.key }) { row ->
                    BrowseRowItem(row, style, now, onOpen)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, @StringRes hint: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(hint)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onChange("") }, modifier = Modifier.testTag(BrowseTags.CLEAR)) {
                    Text(stringResource(R.string.browse_clear))
                }
            }
        },
        // Host and check names are not prose: no capital letter forced on the first character.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(BrowseTags.SEARCH),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <S : Enum<S>> FilterChips(
    style: BrowseStyle<S>,
    view: BrowseView<S>,
    selected: Set<S>,
    showHandled: Boolean,
    onToggleState: (S) -> Unit,
    onToggleHandled: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        style.states.forEach { state ->
            val count = view.counts[state] ?: 0
            // A rare state still gets its chip while it is switched on, so it can be switched off.
            if (state !in style.rareStates || count > 0 || state in selected) {
                Chip(
                    label = stringResource(R.string.browse_chip, stringResource(style.label(state)), count),
                    selected = state in selected,
                    tag = BrowseTags.chip(state),
                    onClick = { onToggleState(state) },
                )
            }
        }
        // Nothing handled, nothing to hide: the switch only appears when it would do something.
        if (view.handledCount > 0 || !showHandled) {
            Chip(
                label = stringResource(R.string.browse_handled, view.handledCount),
                selected = showHandled,
                tag = BrowseTags.HANDLED,
                onClick = onToggleHandled,
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, softWrap = false) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun Empty(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp).testTag(BrowseTags.EMPTY),
    )
}

@Composable
private fun <S : Enum<S>> BrowseRowItem(
    row: BrowseRow<S>,
    style: BrowseStyle<S>,
    now: Instant,
    onOpen: (BrowseRow<S>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row) }
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
