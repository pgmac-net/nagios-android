// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.localnet.LocalNetwork
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.ui.localnet.LocalNetworkRequest
import net.pgmac.nagwatch.ui.problems.ProblemKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Which profile is shown and its status: shared by the Problems, Hosts and Services tabs. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val statuses: StatusRepository,
    private val selectedProfile: SelectedProfile,
    private val network: LocalNetwork,
) : ViewModel() {
    private val filter = MutableStateFlow(emptySet<ProblemKind>())

    /** The profile held back until the question about local network access has an answer. */
    private val awaiting = MutableStateFlow<Long?>(null)

    /** The app's own explanation is on screen. It is off while Android's prompt is up. */
    private val explaining = MutableStateFlow(false)

    /** The profile on screen: the remembered one if it still exists, otherwise the first. */
    private val shownProfileId: Flow<Long?> =
        combine(profiles.observeProfiles(), selectedProfile.id) { all, requested -> selectProfile(all, requested)?.id }
            .distinctUntilChanged()

    init {
        // Loading is driven by *which profile is shown*, not by the screen asking at the right
        // moment. On a cold start the screen starts before the profiles have been read, so a
        // refresh requested then finds no profile and does nothing; this is what loads the data
        // once the profile is known, and again whenever it changes.
        viewModelScope.launch {
            shownProfileId.filterNotNull().collectLatest { id -> load(id, force = false) }
        }
    }

    /** Ticks so ages ("2h 04m") and staleness keep moving while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(statuses.now())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        profiles.observeProfiles(),
        selectedProfile.id,
        statuses.statuses,
        combine(filter, explaining, ::Pair),
        clock,
    ) { all, requested, byProfile, (activeFilter, explain), now ->
        val selected = selectProfile(all, requested)
        HomeUiState(
            loading = false,
            profiles = all,
            selected = selected,
            status = selected?.let { byProfile[it.id] },
            filter = activeFilter,
            now = now,
            explainLocalNetwork = explain,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Choosing a profile is all this does; showing a different profile is what loads it. */
    fun select(profileId: Long) {
        filter.value = emptySet()
        viewModelScope.launch { selectedProfile.select(profileId) }
    }

    fun toggleFilter(kind: ProblemKind) = filter.update { if (kind in it) it - kind else it + kind }

    /** Pull-to-refresh and the retry button: always fetches. */
    fun refresh() {
        val id = state.value.selected?.id ?: return
        load(id, force = true)
    }

    /**
     * On returning to the screen: fetches only if what is held is old. The first load does
     * not depend on this; see `init`.
     */
    fun refreshIfNeeded(profileId: Long? = state.value.selected?.id) {
        val id = profileId ?: return
        load(id, force = false)
    }

    /**
     * The one way a profile is fetched, so that none of them can get ahead of the question about
     * local network access: while it is being asked, nothing is fetched.
     */
    private fun load(profileId: Long, force: Boolean) {
        viewModelScope.launch {
            if (asksFirst(profileId)) return@launch
            if (force) statuses.refresh(profileId) else statuses.refreshIfOlderThan(profileId, RESUME_MAX_AGE)
        }
    }

    /** True if the profile's first load has to wait for the person to be asked (design: docs/adr/0007). */
    private suspend fun asksFirst(profileId: Long): Boolean {
        if (awaiting.value != null) return true
        val host = profiles.get(profileId)?.baseUrl?.toHttpUrlOrNull()?.host ?: return false
        if (!network.shouldAsk(host)) return false
        awaiting.value = profileId
        explaining.value = true
        return true
    }

    /** "Continue": the explanation is done with; the screen now raises Android's prompt. */
    fun localNetworkExplained() {
        explaining.value = false
        viewModelScope.launch { network.explained() }
    }

    /** "Not now". Whatever the answer, the profile is fetched: a VPN needs no permission. */
    fun localNetworkDeclined() {
        viewModelScope.launch {
            network.explained()
            proceed()
        }
    }

    /** Android's prompt came back, or the button's did. Asked-for loads go ahead whatever the answer. */
    fun localNetworkAnswered(granted: Boolean, canAskAgain: Boolean) {
        viewModelScope.launch {
            network.answered(granted, canAskAgain)
            if (awaiting.value != null) {
                proceed()
            } else if (granted) {
                refresh()
            }
        }
    }

    /** The button on the error: Android's prompt, or its settings page once it will not show the prompt. */
    fun allowLocalNetwork(request: LocalNetworkRequest) {
        viewModelScope.launch { if (network.exhausted()) request.openSettings() else request.askAndroid() }
    }

    private suspend fun proceed() {
        val id = awaiting.value ?: return
        awaiting.value = null
        explaining.value = false
        statuses.refresh(id)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 30_000L
        val RESUME_MAX_AGE: Duration = Duration.ofSeconds(60)
    }
}
