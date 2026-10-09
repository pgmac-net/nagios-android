// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.status.StatusRepository

@HiltViewModel
class ProblemsViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val statuses: StatusRepository,
    private val selectedProfile: SelectedProfile,
) : ViewModel() {
    private val filter = MutableStateFlow(emptySet<ProblemKind>())

    /** Ticks so ages ("2h 04m") and staleness keep moving while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(statuses.now())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<ProblemsUiState> = combine(
        profiles.observeProfiles(),
        selectedProfile.id,
        statuses.statuses,
        filter,
        clock,
    ) { all, requested, byProfile, activeFilter, now ->
        val selected = selectProfile(all, requested)
        ProblemsUiState(
            loading = false,
            profiles = all,
            selected = selected,
            status = selected?.let { byProfile[it.id] },
            filter = activeFilter,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProblemsUiState())

    fun select(profileId: Long) {
        filter.value = emptySet()
        viewModelScope.launch {
            selectedProfile.select(profileId)
            statuses.refreshIfOlderThan(profileId, RESUME_MAX_AGE)
        }
    }

    fun toggleFilter(kind: ProblemKind) = filter.update { if (kind in it) it - kind else it + kind }

    /** Pull-to-refresh and the retry button: always fetches. */
    fun refresh() {
        val id = state.value.selected?.id ?: return
        viewModelScope.launch { statuses.refresh(id) }
    }

    /**
     * On opening the screen and on returning to it: shows what is cached straight away,
     * then fetches only if that is old.
     */
    fun refreshIfNeeded(profileId: Long? = state.value.selected?.id) {
        val id = profileId ?: return
        viewModelScope.launch { statuses.refreshIfOlderThan(id, RESUME_MAX_AGE) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 30_000L
        val RESUME_MAX_AGE: Duration = Duration.ofSeconds(60)
    }
}
