// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.ServiceGroup
import net.pgmac.nagwatch.status.ConfigPart
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.message
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

/** The ways a tab can lay out its rows. */
enum class BrowseMode(val label: Int) {
    ALL(R.string.browse_mode_all),
    GROUPS(R.string.browse_mode_groups),
}

/**
 * The configuration the grouped views need, for the profile on screen, and how to ask for it.
 * `force` reads it again even when it is known: the retry after a failure.
 */
class BrowseInventory(
    val hostGroups: ConfigPart<List<HostGroup>>,
    val serviceGroups: ConfigPart<List<ServiceGroup>>,
    val loadHostGroups: (force: Boolean) -> Unit,
    val loadServiceGroups: (force: Boolean) -> Unit,
)

/** The state chips that are on, kept apart for All and for the grouped views. */
internal class ViewStates<S>(private val all: MutableState<Set<S>>, private val grouped: MutableState<Set<S>>) {
    fun of(mode: BrowseMode): Set<S> = holder(mode).value

    fun toggle(mode: BrowseMode, state: S) {
        val held = holder(mode)
        held.value = if (state in held.value) held.value - state else held.value + state
    }

    private fun holder(mode: BrowseMode) = if (mode == BrowseMode.ALL) all else grouped
}

/** One tab's groups, already turned into what rows are matched against. */
internal class GroupsSource(val part: ConfigPart<List<RowGroup>>, val load: (force: Boolean) -> Unit) {
    /** Never read, not being read, and not failed: a failure waits for the button. */
    fun wanted(): Boolean = part.value == null && !part.loading && part.error == null
}

/**
 * Which groups are open: where each starts (open if it needs someone), turned over for each
 * group in [toggled], the ones the person tapped.
 */
internal class GroupOpenness(private val toggled: Set<String>, private val onToggled: (Set<String>) -> Unit) {
    fun isOpen(section: RowSection<*>): Boolean = section.needsSomeone != (section.key in toggled)

    fun toggle(key: String) = onToggled(if (key in toggled) toggled - key else toggled + key)
}

/**
 * What the grouped view puts in the list. False when it has nothing to put there because no
 * row is shown, so the list says why it is empty exactly as the plain view does.
 */
internal fun <S : Enum<S>> LazyListScope.groupsBody(
    source: GroupsSource,
    shown: List<BrowseRow<S>>,
    openness: GroupOpenness,
    style: BrowseStyle<S>,
    row: @Composable (BrowseRow<S>) -> Unit,
): Boolean {
    val known = source.part.value
    when {
        known == null -> item { GroupsPending(source.part, onRetry = { source.load(true) }) }
        shown.isEmpty() -> return false
        else -> groupedRows(BrowseGroups.sections(shown, known), known.isEmpty(), openness, style, row)
    }
    return true
}

/**
 * The grouped rows: a heading per group, and its rows while it is open.
 */
internal fun <S : Enum<S>> LazyListScope.groupedRows(
    sections: List<RowSection<S>>,
    noGroupsDefined: Boolean,
    openness: GroupOpenness,
    style: BrowseStyle<S>,
    row: @Composable (BrowseRow<S>) -> Unit,
) {
    if (noGroupsDefined) item { NoGroupsDefined() }
    sections.forEach { section ->
        val open = openness.isOpen(section)
        item(key = "group\u0001${section.key}") {
            GroupHeader(section, style, open) { openness.toggle(section.key) }
            HorizontalDivider()
        }
        if (open) {
            items(section.rows, key = { "${section.key}\u0001${it.key}" }) {
                row(it)
                HorizontalDivider()
            }
        }
    }
}

internal fun <T> ConfigPart<T>.mapValue(transform: (T) -> List<RowGroup>): ConfigPart<List<RowGroup>> =
    ConfigPart(value?.let(transform), loading, error)

@Composable
internal fun ModeSwitch(selected: BrowseMode, onSelect: (BrowseMode) -> Unit) {
    val modes = BrowseMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag(BrowseTags.MODES)) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.testTag(BrowseTags.mode(mode)),
            ) { Text(stringResource(mode.label)) }
        }
    }
}

/** The groups are not known yet: being read, or the read failed and can be tried again. */
@Composable
internal fun GroupsPending(part: ConfigPart<List<RowGroup>>, onRetry: () -> Unit) {
    val error = part.error
    Column(
        modifier = Modifier.padding(24.dp).testTag(BrowseTags.GROUPS_PENDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error == null) {
            Text(stringResource(R.string.browse_groups_loading), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                stringResource(R.string.browse_groups_failed, error.message()),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag(BrowseTags.GROUPS_RETRY)) {
                Text(stringResource(R.string.browse_groups_retry))
            }
        }
    }
}

@Composable
internal fun NoGroupsDefined() {
    Text(
        text = stringResource(R.string.browse_groups_none_defined),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(BrowseTags.GROUPS_NONE),
    )
}

/** A group's heading: open or closed, its name, how many of its members are shown, and the worst of them. */
@Composable
internal fun <S : Enum<S>> GroupHeader(
    section: RowSection<S>,
    style: BrowseStyle<S>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val label = section.group?.label ?: stringResource(R.string.browse_group_none)
    val state = stringResource(if (open) R.string.browse_group_open else R.string.browse_group_closed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(BrowseTags.group(section.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(section.rows.size.toString(), style = MaterialTheme.typography.labelMedium)
        section.worst?.let { StateBadge(style.label(it), style.color(it, NagwatchTheme.stateColors)) }
    }
}
