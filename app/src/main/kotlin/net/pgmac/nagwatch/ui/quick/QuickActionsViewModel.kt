// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.DEFAULT_ACK_COMMENT
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.status.CommandRepository
import net.pgmac.nagwatch.status.StatusRepository

/**
 * Sends the quick actions of the lists: an acknowledgement, its removal, a recheck.
 *
 * It goes through the same [CommandRepository] the detail screen uses, so a quick action is
 * accepted and then confirmed by reading the object back exactly as a slow one is. What differs
 * is only that nothing is asked first: an acknowledgement takes its comment from the profile.
 */
@HiltViewModel
class QuickActionsViewModel @Inject constructor(
    private val commands: CommandRepository,
    private val profiles: ProfileRepository,
    private val statuses: StatusRepository,
) : ViewModel() {
    private val current = MutableStateFlow(QuickUiState())
    val state: StateFlow<QuickUiState> = current.asStateFlow()

    /** Asked once per profile and remembered by the repository, so opening a menu costs nothing after the first. */
    fun opened(profileId: Long) {
        viewModelScope.launch {
            val permission = commands.permission(profileId)
            current.update { it.copy(permission = permission) }
        }
    }

    /** One command at a time: a second while the first is on its way does nothing. */
    fun run(profileId: Long, action: QuickAction, target: ObjectRef) {
        if (current.value.progress?.inFlight == true) return
        viewModelScope.launch {
            val command = when (action) {
                QuickAction.ACKNOWLEDGE -> Command.Acknowledge(target, comment(profileId))
                QuickAction.REMOVE_ACKNOWLEDGEMENT -> Command.RemoveAcknowledgement(target)
                QuickAction.RECHECK -> Command.ForceCheck(target)
            }
            send(profileId, command)
        }
    }

    /** Only an acknowledgement that has just been confirmed can be taken back this way. */
    fun undo(profileId: Long) {
        val confirmed = current.value.progress as? ActionProgress.Confirmed ?: return
        if (confirmed.command !is Command.Acknowledge) return
        run(profileId, QuickAction.REMOVE_ACKNOWLEDGEMENT, confirmed.command.target)
    }

    /** Only if it is still the news on show: a later command's must not be thrown away with it. */
    fun dismiss(progress: ActionProgress) {
        current.update { if (it.progress === progress && !progress.inFlight) it.copy(progress = null) else it }
    }

    /** The profile's own words, or the app's. The form's flags: not sticky, notify, not persistent. */
    private suspend fun comment(profileId: Long): String =
        profiles.get(profileId)?.quickAckComment ?: DEFAULT_ACK_COMMENT

    private suspend fun send(profileId: Long, command: Command) {
        current.update { it.copy(progress = ActionProgress.Sending(command)) }
        commands.run(profileId, command).collect { progress ->
            // In one step: nobody should see the refusal without what it taught.
            current.update { it.copy(progress = progress, permission = it.permission.after(progress)) }
            // Whether or not the effect was seen, the lists should show what Nagios says now.
            if (progress.changedSomething) statuses.refresh(profileId)
        }
    }

    /** A refusal for being read-only is learned the hard way, but learned. */
    private fun CommandPermission.after(progress: ActionProgress): CommandPermission =
        if (progress is ActionProgress.Refused && progress.reason == Refusal.READ_ONLY) {
            CommandPermission.READ_ONLY
        } else {
            this
        }

    private val ActionProgress.changedSomething: Boolean
        get() = this is ActionProgress.Confirmed || this is ActionProgress.NotConfirmed ||
            this is ActionProgress.Unknown
}
