// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Which widget shows which profile, in memory. */
class FakeWidgetBindings(initial: Map<Int, Long> = emptyMap()) : WidgetBindings {
    private val state = MutableStateFlow(initial)

    override val watched: Flow<Set<Long>> = state.map { it.values.toSet() }

    override suspend fun profileOf(appWidgetId: Int): Long? = state.value[appWidgetId]

    override suspend fun bind(appWidgetId: Int, profileId: Long) = state.update { it + (appWidgetId to profileId) }

    override suspend fun unbind(appWidgetIds: Collection<Int>) = state.update { it - appWidgetIds.toSet() }

    override suspend fun retainOnly(appWidgetIds: Collection<Int>) =
        state.update { all -> all.filterKeys { it in appWidgetIds } }
}
