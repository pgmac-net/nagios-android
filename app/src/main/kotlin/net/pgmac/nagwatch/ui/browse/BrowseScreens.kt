// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.ui.home.StateChip
import net.pgmac.nagwatch.ui.home.StatusBanners
import net.pgmac.nagwatch.ui.home.UpdatedLabel
import net.pgmac.nagwatch.ui.quick.QuickMenu
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.theme.of

@Composable
internal fun HostsTab(
    status: ProfileStatus,
    now: Instant,
    onOpen: (hostName: String) -> Unit,
    onAllowLocalNetwork: () -> Unit,
    quick: QuickMenu? = null,
    inventory: BrowseInventory? = null,
) {
    val snapshot = status.snapshot
    val report = status.report
    val rows = remember(snapshot, report) {
        if (snapshot == null || report == null) emptyList() else BrowseRows.hosts(snapshot, report)
    }
    val groups = inventory?.let { inv ->
        val part = remember(inv.hostGroups) { inv.hostGroups.mapValue(BrowseGroups::hostGroups) }
        GroupsSource(part, inv.loadHostGroups)
    }
    val parents = inventory?.let { ParentsSource(it.hostConfigs, it.loadHostConfigs, it.openMap) }
    BrowseList(
        rows,
        HostStyle,
        status,
        now,
        onOpen = { onOpen(it.hostName) },
        onAllowLocalNetwork = onAllowLocalNetwork,
        quick = quick,
        groups = groups,
        parents = parents,
    )
}

@Composable
internal fun ServicesTab(
    status: ProfileStatus,
    now: Instant,
    onOpen: (hostName: String, description: String) -> Unit,
    onAllowLocalNetwork: () -> Unit,
    quick: QuickMenu? = null,
    inventory: BrowseInventory? = null,
) {
    val snapshot = status.snapshot
    val report = status.report
    val rows = remember(snapshot, report) {
        if (snapshot == null || report == null) emptyList() else BrowseRows.services(snapshot, report)
    }
    val groups = inventory?.let { inv ->
        val part = remember(inv.serviceGroups) { inv.serviceGroups.mapValue(BrowseGroups::serviceGroups) }
        GroupsSource(part, inv.loadServiceGroups)
    }
    BrowseList(
        rows,
        ServiceStyle,
        status,
        now,
        onOpen = { onOpen(it.hostName, it.service.orEmpty()) },
        onAllowLocalNetwork = onAllowLocalNetwork,
        quick = quick,
        groups = groups,
    )
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
    onAllowLocalNetwork: (() -> Unit)? = null,
    initialStates: Set<S> = style.defaultStates,
    quick: QuickMenu? = null,
    /** Null: the tab has no grouped view. With neither, there is no switch. */
    groups: GroupsSource? = null,
    /** Null: the tab has no Parents view. */
    parents: ParentsSource? = null,
) {
    // Kept here rather than in the view model: what is typed must reach the text field in the
    // same frame, and the tab's saved state already outlives switching to another tab.
    var query by rememberSaveable { mutableStateOf("") }
    // All opens on what is wrong; a grouped view is for looking around, so it opens on every
    // state. Each keeps its own chips, so going back to All finds them as they were.
    val chips =
        ViewStates(rememberSaveable { mutableStateOf(initialStates) }, rememberSaveable { mutableStateOf(emptySet()) })
    var showHandled by rememberSaveable { mutableStateOf(BrowseFilter<S>().showHandled) }
    var chosenMode by rememberSaveable { mutableStateOf(BrowseMode.ALL) }
    val sources = listOfNotNull<ViewSource>(groups, parents)
    val source = sources.firstOrNull { it.mode == chosenMode }
    val mode = source?.mode ?: BrowseMode.ALL
    val states = chips.of(mode)
    val view = remember(rows, query, states, showHandled) { BrowseFilter(query, states, showHandled).apply(rows) }
    // Which groups the person opened or closed, against where each starts (open if it needs someone).
    var toggled by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val part = source?.part
    // What a view needs is read the first time it is wanted, and not again until a refresh.
    LaunchedEffect(mode, part?.value == null, part?.loading, part?.error) {
        if (source?.wanted() == true) source.load(false)
    }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query, style.searchHint, onChange = { query = it })
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(BrowseTags.LIST)) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (sources.isNotEmpty()) {
                        ModeSwitch(listOf(BrowseMode.ALL) + sources.map { it.mode }, mode) { chosenMode = it }
                    }
                    FilterChips(
                        style = style,
                        view = view,
                        selected = states,
                        showHandled = showHandled,
                        onToggleState = { chips.toggle(mode, it) },
                        onToggleHandled = { showHandled = !showHandled },
                    )
                    status.lastSuccess?.let { UpdatedLabel(it) }
                }
            }
            item { StatusBanners(status, now, onAllowLocalNetwork) }
            val viewRows = ViewRows(view.rows, rows, style) { row -> BrowseRowItem(row, style, now, onOpen, quick) }
            val grouped = source?.draw(this, viewRows, GroupOpenness(toggled) { toggled = it }) == true
            if (!grouped && view.rows.isEmpty()) {
                // Hiding handled problems is the default, so when that is all that stands between
                // the person and a row, the list has to say so.
                val hiddenHandled = if (showHandled) {
                    0
                } else {
                    BrowseFilter(query, states, showHandled = true).apply(rows).rows.size
                }
                if (hiddenHandled > 0) {
                    item { EmptyHandledHidden(hiddenHandled) }
                } else {
                    item { Empty(emptyText(rows.isEmpty(), query.isNotBlank(), states.isNotEmpty(), style)) }
                }
            } else if (!grouped) {
                items(view.rows, key = { it.key }) { row ->
                    BrowseRowItem(row, style, now, onOpen, quick)
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
        style.chips.states.forEach { state ->
            val count = view.counts[state] ?: 0
            // A rare state still gets its chip while it is switched on, so it can be switched off.
            if (state !in style.chips.rare || count > 0 || state in selected) {
                StateChip(
                    label = stringResource(R.string.browse_chip, stringResource(style.label(state)), count),
                    selected = state in selected,
                    color = style.color(state, NagwatchTheme.stateColors),
                    tag = BrowseTags.chip(state),
                    onClick = { onToggleState(state) },
                )
            }
        }
        // Nothing handled, nothing to hide: the switch only appears when it would do something,
        // which includes while it is on, so it can be switched off again.
        if (view.handledCount > 0 || showHandled) {
            StateChip(
                label = stringResource(R.string.browse_handled, view.handledCount),
                selected = showHandled,
                color = null,
                tag = BrowseTags.HANDLED,
                onClick = onToggleHandled,
            )
        }
    }
}

/**
 * Why the list is empty. With chips on by default, "nothing here" most often means nothing is
 * wrong, and that has to read differently from a search that found nothing.
 */
private fun emptyText(nothingAtAll: Boolean, searching: Boolean, chipsOn: Boolean, style: BrowseStyle<*>): Int = when {
    nothingAtAll -> style.emptyText
    searching && chipsOn -> R.string.browse_none_searched_in_states
    chipsOn -> R.string.browse_none_in_states
    else -> R.string.browse_none
}

@Composable
private fun EmptyHandledHidden(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.browse_none_handled_hidden, count, count),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp).testTag(BrowseTags.EMPTY_HANDLED_HIDDEN),
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
