// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.quick

import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandPermission

object QuickTags {
    const val MENU = "quick_menu"
    const val READ_ONLY = "quick_read_only"
    const val OPEN = "quick_open"

    fun item(action: QuickAction) = "quick_${action.name}"
}

/**
 * A host or service as a quick action sees it: which one, and which of the things that can be
 * done to it make sense. The rules are the detail screen's own (`DetailUiState`).
 */
data class QuickTarget(val ref: ObjectRef, val isProblem: Boolean, val acknowledged: Boolean) {
    /** Nagios drops an acknowledgement of something that is not a problem, so it is not offered. */
    val canAcknowledge: Boolean get() = isProblem && !acknowledged

    val canRemoveAcknowledgement: Boolean get() = acknowledged
}

/**
 * What can be done to a host or service without opening it and without a form. Each is sent at
 * once (docs/adr/0012). Anything that needs words or a window (a comment, a downtime) is not
 * here, and stays on the detail screen.
 */
enum class QuickAction { ACKNOWLEDGE, REMOVE_ACKNOWLEDGEMENT, RECHECK }

/** What the lists show of quick actions: whether they may be used, and what became of the last. */
data class QuickUiState(
    val permission: CommandPermission = CommandPermission.UNKNOWN,
    val progress: ActionProgress? = null,
) {
    /** One command at a time, and none at all for a user Nagios takes no commands from. */
    val enabled: Boolean get() = permission != CommandPermission.READ_ONLY && progress?.inFlight != true

    val readOnly: Boolean get() = permission == CommandPermission.READ_ONLY
}

/**
 * Everything the quick actions know, across profiles. A list is shown one profile's view of it
 * ([forProfile]): what one Nagios says its user may do says nothing about another, and the
 * profile on screen can change while a command sent to the last one is still on its way.
 */
data class QuickState(
    val permissions: Map<Long, CommandPermission> = emptyMap(),
    val progress: ActionProgress? = null,
    /** The profile [progress] is about. It is what an undo is sent to, whatever is on screen by then. */
    val sentTo: Long? = null,
) {
    /**
     * The news is shown whichever profile is on screen: it is still true, and one command at a
     * time holds across profiles. The permission is that profile's alone.
     */
    fun forProfile(profileId: Long) = QuickUiState(permissions[profileId] ?: CommandPermission.UNKNOWN, progress)
}

/** What a list can ask for. The profile is the one on screen; whoever implements this knows it. */
interface QuickActions {
    /** A menu has been opened: find out, if it is not known yet, whether this user may send commands. */
    fun opened()

    fun run(action: QuickAction, target: ObjectRef)

    /** Takes back the acknowledgement that was just confirmed. */
    fun undo()

    /** The news about [progress] has been read, or has had its time. */
    fun dismiss(progress: ActionProgress)
}

/** Quick actions as a list is handed them. Null where a list is shown without them. */
class QuickMenu(val state: QuickUiState, val actions: QuickActions)
