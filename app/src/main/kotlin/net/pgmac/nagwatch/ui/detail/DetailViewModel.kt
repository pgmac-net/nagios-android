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
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.status.CommandRepository
import net.pgmac.nagwatch.status.DetailRepository
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.StatusRepository

/** One host or service, named by the navigation arguments it was opened with. */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val details: DetailRepository,
    private val statuses: StatusRepository,
    private val commands: CommandRepository,
) : ViewModel() {
    val profileId: Long = checkNotNull(savedState[ARG_PROFILE]) { "no profile" }
    val ref = ObjectRef(
        hostName = checkNotNull(savedState[ARG_HOST]) { "no host" },
        description = savedState[ARG_SERVICE],
    )

    private val detail = MutableStateFlow(ObjectDetail(ref))
    private var loading: Job? = null
    private val permission = MutableStateFlow(CommandPermission.UNKNOWN)
    private val action = MutableStateFlow<ActionProgress?>(null)
    private val serverClock = MutableStateFlow<ServerClock?>(null)

    /** Ticks so "for 2h 04m" keeps moving while the screen is open. */
    private val clock = flow {
        while (true) {
            emit(statuses.now())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<DetailUiState> =
        combine(detail, statuses.statuses, clock, commanding()) { held, byProfile, now, commands ->
            detailUiState(held, byProfile[profileId], now).copy(
                permission = commands.first,
                action = commands.second,
                serverClock = commands.third,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState(ObjectDetail(ref)))

    init {
        // Loaded because the view model exists, not because a screen asked at the right moment.
        viewModelScope.launch {
            // After the process was killed the last poll is no longer in memory; it is on disk.
            statuses.showCached(profileId)
        }
        load(from = null)
        // One request that changes nothing, once per profile for as long as the app runs.
        viewModelScope.launch { permission.value = commands.permission(profileId) }
    }

    fun acknowledge(comment: String, sticky: Boolean, notify: Boolean, persistent: Boolean) =
        send(Command.Acknowledge(ref, comment, sticky = sticky, notify = notify, persistent = persistent))

    fun removeAcknowledgement() = send(Command.RemoveAcknowledgement(ref))

    fun addComment(comment: String) = send(Command.AddComment(ref, comment))

    fun forceCheck() = send(Command.ForceCheck(ref))

    fun scheduleDowntime(comment: String, window: DowntimeWindow) {
        val (start, end) = window.resolve(statuses.now())
        send(Command.ScheduleDowntime(ref, comment, start, end))
    }

    fun cancelDowntime(id: Long) = send(Command.CancelDowntime(ref, id))

    /**
     * The answer to "does this server put the day or the month first?", asked because a
     * downtime could not be written without it. Remembered, and the downtime sent.
     */
    fun answerDateOrder(format: ServerDateFormat) {
        val waiting = action.value as? ActionProgress.DateOrderNeeded ?: return
        action.value = null
        viewModelScope.launch {
            commands.rememberDateOrder(profileId, format)
            send(waiting.command)
        }
    }

    /** The question was declined: nothing was sent, and nothing is. */
    fun dismissDateOrder() {
        if (action.value is ActionProgress.DateOrderNeeded) action.value = null
    }

    /** Asked for when the downtime form opens, so the window can be shown as the server will read it. */
    fun loadServerClock() {
        viewModelScope.launch { serverClock.value = commands.serverClock(profileId, ref) ?: serverClock.value }
    }

    private fun commanding() = combine(permission, action, serverClock) { may, sent, clock -> Triple(may, sent, clock) }

    /** One command at a time: a second tap while the first is on its way does nothing. */
    private fun send(command: Command) {
        if (action.value?.inFlight == true) return
        action.value = ActionProgress.Sending(command)
        viewModelScope.launch {
            commands.run(profileId, command).collect { progress ->
                action.value = progress
                settled(progress)
            }
        }
    }

    /** Once a command has an answer, what is on screen is brought up to date with it. */
    private fun settled(progress: ActionProgress) {
        when (progress) {
            is ActionProgress.Sending, is ActionProgress.Sent, is ActionProgress.NotSent,
            is ActionProgress.DateOrderNeeded,
            -> Unit

            is ActionProgress.Refused ->
                if (progress.reason == Refusal.READ_ONLY) permission.value = CommandPermission.READ_ONLY

            // Whether or not the effect was seen, the screen should show what Nagios says now.
            is ActionProgress.NotConfirmed, is ActionProgress.Unknown, is ActionProgress.Mismatch -> refresh()

            is ActionProgress.Confirmed -> {
                refresh()
                // The lists behind this screen show acknowledged problems differently.
                viewModelScope.launch { statuses.refresh(profileId) }
            }
        }
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
