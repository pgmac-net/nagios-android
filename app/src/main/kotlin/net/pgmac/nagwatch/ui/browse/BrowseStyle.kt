// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.ui.theme.StateColors
import net.pgmac.nagwatch.ui.theme.of
import net.pgmac.nagwatch.ui.theme.shortWord

/** The chips of one list: every state in chip order, and which are special. */
internal class BrowseChips<S : Enum<S>>(
    val states: List<S>,
    /** States with a chip only while something is in them, to keep the row of chips short. */
    val rare: Set<S>,
    /**
     * The chips that are on when the tab is first opened: what is wrong. Everything else is a
     * tap away, and its chip says how many there are.
     */
    val onByDefault: Set<S>,
)

/** How one kind of object is drawn: its chips, and the words and colour for each state. */
internal class BrowseStyle<S : Enum<S>>(
    val chips: BrowseChips<S>,
    @param:StringRes val searchHint: Int,
    @param:StringRes val emptyText: Int,
    val label: (S) -> Int,
    val color: (S, StateColors) -> Color,
) {
    val defaultStates: Set<S> get() = chips.onByDefault
}

internal val HostStyle = BrowseStyle(
    chips = BrowseChips(
        states = listOf(HostState.UP, HostState.DOWN, HostState.UNREACHABLE, HostState.PENDING),
        rare = setOf(HostState.PENDING),
        onByDefault = setOf(HostState.DOWN, HostState.UNREACHABLE),
    ),
    searchHint = R.string.browse_search_hosts,
    emptyText = R.string.browse_empty_hosts,
    label = HostState::shortWord,
    color = { state, colors -> colors.of(state) },
)

internal val ServiceStyle = BrowseStyle(
    chips = BrowseChips(
        states = listOf(
            ServiceState.OK,
            ServiceState.WARNING,
            ServiceState.CRITICAL,
            ServiceState.UNKNOWN,
            ServiceState.PENDING,
        ),
        rare = setOf(ServiceState.PENDING),
        onByDefault = setOf(ServiceState.WARNING, ServiceState.CRITICAL),
    ),
    searchHint = R.string.browse_search_services,
    emptyText = R.string.browse_empty_services,
    label = ServiceState::shortWord,
    color = { state, colors -> colors.of(state) },
)
