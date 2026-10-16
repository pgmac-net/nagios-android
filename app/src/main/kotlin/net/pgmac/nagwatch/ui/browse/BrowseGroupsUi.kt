// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostConfig
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
    PARENTS(R.string.browse_mode_parents),
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
    val hostConfigs: ConfigPart<List<HostConfig>> = ConfigPart(),
    val loadHostConfigs: (force: Boolean) -> Unit = {},
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

/**
 * A view other than All: the configuration it needs, how to ask for it, and how it draws the
 * shown rows once it has it.
 */
internal sealed class ViewSource(val mode: BrowseMode, val load: (force: Boolean) -> Unit) {
    abstract val part: ConfigPart<*>

    /** Never read, not being read, and not failed: a failure waits for the button. */
    fun wanted(): Boolean = part.value == null && !part.loading && part.error == null

    /**
     * Puts the view in the list. False when it has nothing to put there because no row is
     * shown, so the list says why it is empty exactly as All does.
     */
    fun <S : Enum<S>> draw(scope: LazyListScope, rows: ViewRows<S>, openness: GroupOpenness): Boolean {
        val known = part.value
        when {
            known == null -> scope.item { ViewPending(this@ViewSource, onRetry = { load(true) }) }
            rows.shown.isEmpty() -> return false
            else -> drawKnown(scope, rows, openness)
        }
        return true
    }

    protected abstract fun <S : Enum<S>> drawKnown(scope: LazyListScope, rows: ViewRows<S>, openness: GroupOpenness)
}

/** What a view draws from: the rows picked, every row, how to draw each, and their style. */
internal class ViewRows<S : Enum<S>>(
    val shown: List<BrowseRow<S>>,
    val all: List<BrowseRow<S>>,
    val style: BrowseStyle<S>,
    val row: @Composable (BrowseRow<S>) -> Unit,
)

/** One tab's groups, already turned into what rows are matched against. */
internal class GroupsSource(override val part: ConfigPart<List<RowGroup>>, load: (force: Boolean) -> Unit) :
    ViewSource(BrowseMode.GROUPS, load) {
    override fun <S : Enum<S>> drawKnown(scope: LazyListScope, rows: ViewRows<S>, openness: GroupOpenness) {
        val known = part.value.orEmpty()
        scope.groupedRows(BrowseGroups.sections(rows.shown, known), known.isEmpty(), openness, rows.style, rows.row)
    }
}

/** Every host's parents, for the Parents view of the Hosts tab. */
internal class ParentsSource(override val part: ConfigPart<List<HostConfig>>, load: (force: Boolean) -> Unit) :
    ViewSource(BrowseMode.PARENTS, load) {
    override fun <S : Enum<S>> drawKnown(scope: LazyListScope, rows: ViewRows<S>, openness: GroupOpenness) {
        val lines = HostTree.lines(rows.shown, rows.all, part.value.orEmpty(), openness::isOpen)
        scope.items(lines, key = { "tree\u0001${it.key}" }) { line ->
            TreeLineItem(line, onToggle = { openness.toggle(line.key) }, row = rows.row)
            HorizontalDivider()
        }
    }
}

/**
 * Which groups are open: where each starts (open if it needs someone), turned over for each
 * group in [toggled], the ones the person tapped.
 */
internal class GroupOpenness(private val toggled: Set<String>, private val onToggled: (Set<String>) -> Unit) {
    fun isOpen(section: RowSection<*>): Boolean = isOpen(section.key, section.needsSomeone)

    fun isOpen(key: String, startsOpen: Boolean): Boolean = startsOpen != (key in toggled)

    fun toggle(key: String) = onToggled(if (key in toggled) toggled - key else toggled + key)
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
internal fun ModeSwitch(modes: List<BrowseMode>, selected: BrowseMode, onSelect: (BrowseMode) -> Unit) {
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

/** What a view needs is not known yet: being read, or the read failed and can be tried again. */
@Composable
internal fun ViewPending(source: ViewSource, onRetry: () -> Unit) {
    val error = source.part.error
    val parents = source.mode == BrowseMode.PARENTS
    Column(
        modifier = Modifier.padding(24.dp).testTag(BrowseTags.GROUPS_PENDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error == null) {
            Text(
                stringResource(if (parents) R.string.browse_parents_loading else R.string.browse_groups_loading),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(
                    if (parents) R.string.browse_parents_failed else R.string.browse_groups_failed,
                    error.message(),
                ),
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

/**
 * A host in the Parents view: indented by its depth, with a button that opens or closes what is
 * below it, and its other parents named underneath so the tree never hides one.
 */
@Composable
internal fun <S : Enum<S>> TreeLineItem(
    line: TreeLine<S>,
    onToggle: () -> Unit,
    row: @Composable (BrowseRow<S>) -> Unit,
) {
    val name = line.row.hostName
    Row(
        modifier = Modifier.fillMaxWidth().testTag(BrowseTags.tree(line.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TREE_INDENT * line.depth))
        if (line.hasChildren) {
            val action = stringResource(
                if (line.open) R.string.browse_tree_close else R.string.browse_tree_open,
                name,
            )
            TextButton(
                onClick = onToggle,
                modifier = Modifier
                    .semantics { contentDescription = action }
                    .testTag(BrowseTags.treeToggle(line.key)),
            ) { Text(if (line.open) "\u25BE" else "\u25B8", style = MaterialTheme.typography.titleMedium) }
        } else {
            Spacer(Modifier.width(TREE_TOGGLE_WIDTH))
        }
        Column(modifier = Modifier.weight(1f).alpha(if (line.context) CONTEXT_ALPHA else 1f)) {
            row(line.row)
            if (line.alsoUnder.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.browse_tree_also_under, line.alsoUnder.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private val TREE_INDENT = 16.dp
private val TREE_TOGGLE_WIDTH = 48.dp

/** A host drawn only as the way down to one that was picked. */
private const val CONTEXT_ALPHA = 0.6f
