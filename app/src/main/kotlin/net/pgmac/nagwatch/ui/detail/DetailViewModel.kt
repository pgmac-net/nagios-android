// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.status.DetailRepository
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.StatusRepository

/** One host or service, named by the navigation arguments it was opened with. */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val details: DetailRepository,
    private val statuses: StatusRepository,
) : ViewModel() {
    val profileId: Long = checkNotNull(savedState[ARG_PROFILE]) { "no profile" }
    val ref = ObjectRef(
        hostName = checkNotNull(savedState[ARG_HOST]) { "no host" },
        description = savedState[ARG_SERVICE],
    )

    private val detail = MutableStateFlow(ObjectDetail(ref))
    private var loading: Job? = null

    /** Ticks so "for 2h 04m" keeps moving while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(statuses.now())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<DetailUiState> = combine(detail, statuses.statuses, clock) { held, byProfile, now ->
        detailUiState(held, byProfile[profileId], now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState(ObjectDetail(ref)))

    init {
        // Loaded because the view model exists, not because a screen asked at the right moment.
        viewModelScope.launch {
            // After the process was killed the last poll is no longer in memory; it is on disk.
            statuses.showCached(profileId)
        }
        load(from = null)
    }

    /** Pull-to-refresh and "Try again": fetches again, keeping what is on screen meanwhile. */
    fun refresh() = load(from = detail.value)

    private fun load(from: ObjectDetail?) {
        loading?.cancel()
        loading = viewModelScope.launch {
            // A refresh that was cut short left "refreshing" set; start from what was last complete.
            val start = from?.copy(refreshing = false)
            details.open(profileId, ref, start).collect { detail.value = it }
        }
    }

    companion object {
        // The same names the navigation routes use, so the arguments arrive here untouched.
        const val ARG_PROFILE = "profile"
        const val ARG_HOST = "host"
        const val ARG_SERVICE = "service"

        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TICK_MS = 30_000L
    }
}
