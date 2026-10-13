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
    val hostName: String = "",
    val period: GraphPeriod = GraphPeriod.DAY,
    /** Being fetched, and there is reason to think it will come: room is left for it. */
    val loading: Boolean = false,
    /** While another period loads, the one already on screen stays. */
    val graph: GraphState.Shown? = null,
    /**
     * The last time of asking got no answer, and there is nothing to show. Only the viewer says
     * so, where a graph was asked for; the detail screen already says the server did not answer.
     */
    val unreachable: Boolean = false,
) {
    /** What the graph is of, as the detail screen names it. */
    val title: String get() = if (service.isEmpty()) hostName else "$hostName / $service"

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
        MutableStateFlow(GraphUiState(service = ref.description.orEmpty(), hostName = ref.hostName))
    val state: StateFlow<GraphUiState> = mutableState.asStateFlow()

    private var size: GraphSize? = null
    private var loading: Job? = null

    init {
        // The period is the profile's, not this screen's. Chosen in the viewer, it is the detail
        // screen's too when the viewer closes; on a phone it was not, and the detail screen went
        // back to showing, and asking for, the period it had been left on.
        viewModelScope.launch {
            graphs.period(profileId).collect { period ->
                val changed = period != mutableState.value.period
                mutableState.update { it.copy(period = period) }
                if (changed) load(refresh = false)
            }
        }
    }

    /**
     * The screen knows how much room it has, and so what size to ask for. Called on every
     * layout; only a different size is a different question.
     */
    fun show(size: GraphSize) {
        if (this.size == size) return
        this.size = size
        load(refresh = false)
    }

    /**
     * Remembered for the profile while the app runs. This view model hears of it the way every
     * other one showing this profile does, and loads the graph then.
     */
    fun choose(period: GraphPeriod) = graphs.choose(profileId, period)

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
                settle(graphs.graph(profileId, ref, period, size, refresh))
            } else {
                settle(GraphState.None)
            }
        }
    }

    private fun settle(answer: GraphState) {
        when (answer) {
            is GraphState.Shown -> mutableState.update { it.copy(loading = false, graph = answer, unreachable = false) }

            GraphState.None -> mutableState.update { it.copy(loading = false, graph = null, unreachable = false) }

            GraphState.Unavailable -> {
                // A failure that may pass takes nothing away. On a phone, with the server down, a
                // period not yet fetched emptied the whole section, chips and all, and left the
                // profile on a period it could not show.
                val shown = mutableState.value.graph
                mutableState.update { it.copy(loading = false, unreachable = shown == null) }
                // Back to the period the picture is of, so the chips say what is on screen.
                if (shown != null) graphs.choose(profileId, shown.period)
            }
        }
    }
}
