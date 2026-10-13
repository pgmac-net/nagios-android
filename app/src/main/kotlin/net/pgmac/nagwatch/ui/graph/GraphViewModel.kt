// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.status.GraphRepository
import net.pgmac.nagwatch.status.GraphState
import net.pgmac.nagwatch.ui.detail.DetailViewModel

/**
 * What a graph's place on a screen is showing.
 *
 * There is no error in it. A graph is there, or is on its way, or there is nothing: every
 * reason for nothing looks the same, because none of them is something the person can act on
 * from here.
 */
data class GraphUiState(
    /** The service's name, for the picture's description. Empty for a host, which has no graph. */
    val service: String = "",
    val period: GraphPeriod = GraphPeriod.DAY,
    /** Being fetched, and there is reason to think it will come: room is left for it. */
    val loading: Boolean = false,
    /** While another period loads, the one already on screen stays. */
    val graph: GraphState.Shown? = null,
) {
    /** Whether the graph has any place on the screen at all. */
    val visible: Boolean get() = loading || graph != null
}

/**
 * One service's graph, named by the same navigation arguments as its detail screen. Used twice:
 * beside the detail screen, and by the viewer that gives the graph the whole screen. Each asks
 * for its own size.
 */
@HiltViewModel
class GraphViewModel @Inject constructor(savedState: SavedStateHandle, private val graphs: GraphRepository) :
    ViewModel() {
    private val profileId: Long = checkNotNull(savedState[DetailViewModel.ARG_PROFILE]) { "no profile" }
    private val ref = ObjectRef(
        hostName = checkNotNull(savedState[DetailViewModel.ARG_HOST]) { "no host" },
        description = savedState[DetailViewModel.ARG_SERVICE],
    )

    private val mutableState =
        MutableStateFlow(GraphUiState(service = ref.description.orEmpty(), period = graphs.period(profileId)))
    val state: StateFlow<GraphUiState> = mutableState.asStateFlow()

    private var size: GraphSize? = null
    private var loading: Job? = null

    /**
     * The screen knows how much room it has, and so what size to ask for. Called on every
     * layout; only a different size is a different question.
     */
    fun show(size: GraphSize) {
        if (this.size == size) return
        this.size = size
        load(refresh = false)
    }

    /** Remembered for the profile while the app runs, so the next graph opens on the same period. */
    fun choose(period: GraphPeriod) {
        if (period == mutableState.value.period) return
        graphs.choose(profileId, period)
        mutableState.update { it.copy(period = period) }
        load(refresh = false)
    }

    /** The person asked again. Nothing remembered is trusted, including "there is no graph". */
    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        val size = size ?: return
        loading?.cancel()
        loading = viewModelScope.launch {
            val period = mutableState.value.period
            // Asked first, and answered without a request: is there any point leaving room?
            val expected = graphs.expects(profileId, ref)
            if (expected || refresh) {
                mutableState.update { it.copy(loading = expected) }
                val answer = graphs.graph(profileId, ref, period, size, refresh)
                mutableState.update { it.copy(loading = false, graph = answer as? GraphState.Shown) }
            } else {
                mutableState.update { it.copy(loading = false, graph = null) }
            }
        }
    }
}
