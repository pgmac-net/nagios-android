// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.storedCommentText
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.ui.home.message
import net.pgmac.nagwatch.ui.toUiText

/**
 * What can be done to this host or service, and what became of the last thing that was.
 *
 * A button that cannot work is shown disabled with the reason beside it, not hidden: a
 * missing button looks like a missing feature.
 *
 * The two actions that need words (acknowledge, comment) open a form here in the page, in
 * place of the buttons, not in a dialog: it stays clear of the keyboard, and it can be
 * tested, which a text field in a dialog window cannot be with the tools this project uses.
 */
@Composable
internal fun Actions(state: DetailUiState, actions: DetailActions) {
    var open by rememberSaveable { mutableStateOf(ActionForm.NONE) }
    val close = { open = ActionForm.NONE }
    Section(R.string.detail_actions, Modifier.testTag(DetailTags.ACTIONS)) {
        when (open) {
            ActionForm.ACKNOWLEDGE -> AcknowledgeForm(close) { comment, sticky, notify, persistent ->
                close()
                actions.acknowledge(comment, sticky, notify, persistent)
            }

            ActionForm.COMMENT -> CommentForm(close) { comment ->
                close()
                actions.addComment(comment)
            }

            ActionForm.NONE, ActionForm.REMOVE_ACKNOWLEDGEMENT -> Buttons(state, actions) { open = it }
        }
        if (state.permission == CommandPermission.READ_ONLY) {
            Text(
                text = stringResource(R.string.actions_read_only),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(DetailTags.READ_ONLY),
            )
        }
        // What became of the last command belongs with the buttons, not under a form for the next one.
        val formOpen = open == ActionForm.ACKNOWLEDGE || open == ActionForm.COMMENT
        state.action?.takeUnless { formOpen }?.let { Progress(it) }
    }
    if (open == ActionForm.REMOVE_ACKNOWLEDGEMENT) {
        RemoveAcknowledgementDialog(close) {
            close()
            actions.removeAcknowledgement()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(state: DetailUiState, actions: DetailActions, onOpen: (ActionForm) -> Unit) {
    val enabled = state.actionsEnabled
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canAcknowledge) {
            ActionButton(R.string.action_acknowledge, DetailTags.ACKNOWLEDGE, enabled) {
                onOpen(ActionForm.ACKNOWLEDGE)
            }
        }
        if (state.canRemoveAcknowledgement) {
            ActionButton(R.string.action_remove_acknowledgement, DetailTags.REMOVE_ACKNOWLEDGEMENT, enabled) {
                onOpen(ActionForm.REMOVE_ACKNOWLEDGEMENT)
            }
        }
        // Asking for a check harms nothing and is easily asked again, so it is not confirmed.
        ActionButton(R.string.action_recheck, DetailTags.RECHECK, enabled, actions::forceCheck)
        ActionButton(R.string.action_comment, DetailTags.COMMENT, enabled) { onOpen(ActionForm.COMMENT) }
    }
}

@Composable
private fun ActionButton(label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(stringResource(label))
    }
}

/** Where the last command has got to. Announced as it changes, for anyone not looking. */
@Composable
private fun Progress(progress: ActionProgress) {
    val failed = progress is ActionProgress.Refused ||
        progress is ActionProgress.NotSent ||
        progress is ActionProgress.Unknown
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress.inFlight) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = progress.text(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(DetailTags.PROGRESS),
        )
    }
}

@Composable
private fun ActionProgress.text(): String = when (this) {
    is ActionProgress.Sending -> stringResource(R.string.progress_sending)

    is ActionProgress.Sent -> stringResource(R.string.progress_sent)

    is ActionProgress.Confirmed -> stringResource(command.confirmedText())

    is ActionProgress.NotConfirmed -> stringResource(R.string.progress_not_confirmed)

    is ActionProgress.Refused -> refusalText(reason, detail)

    is ActionProgress.NotSent -> stringResource(R.string.progress_not_sent, error.message())

    is ActionProgress.Unknown -> when (val why = error) {
        null -> stringResource(R.string.progress_unknown)
        else -> stringResource(R.string.progress_unknown_because, why.toUiText().resolve())
    }
}

private fun Command.confirmedText(): Int = when (this) {
    is Command.Acknowledge -> R.string.progress_confirmed_acknowledge
    is Command.RemoveAcknowledgement -> R.string.progress_confirmed_remove_acknowledgement
    is Command.AddComment -> R.string.progress_confirmed_comment
    is Command.ForceCheck -> R.string.progress_confirmed_recheck
    is Command.ScheduleDowntime, is Command.CancelDowntime -> R.string.progress_confirmed_other
}

/** Nagios' own words are shown only where they say something the app's do not. */
@Composable
private fun refusalText(reason: Refusal, detail: String): String = when (reason) {
    Refusal.READ_ONLY -> stringResource(R.string.refused_read_only)

    Refusal.NOT_AUTHORISED -> stringResource(R.string.refused_not_authorised)

    Refusal.INVALID -> when {
        detail.isBlank() -> stringResource(R.string.refused_invalid_no_detail)
        else -> stringResource(R.string.refused_invalid, detail)
    }

    Refusal.COMMANDS_DISABLED -> stringResource(R.string.refused_commands_disabled)

    Refusal.COULD_NOT_WRITE -> stringResource(R.string.refused_could_not_write)

    Refusal.AUTHENTICATION_DISABLED -> stringResource(R.string.refused_authentication_disabled)
}

/**
 * Sticky starts off, unlike in Nagios' own web interface: a sticky acknowledgement outlives
 * the problem getting worse, and so can hide it. The other two start as Nagios has them.
 * Nothing chosen here is remembered for next time.
 */
@Composable
private fun AcknowledgeForm(onCancel: () -> Unit, onSend: (String, Boolean, Boolean, Boolean) -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    var sticky by rememberSaveable { mutableStateOf(false) }
    var notify by rememberSaveable { mutableStateOf(true) }
    var persistent by rememberSaveable { mutableStateOf(false) }
    Form(
        title = R.string.acknowledge_title,
        send = R.string.action_acknowledge,
        canSend = storedCommentText(comment).isNotEmpty(),
        onCancel = onCancel,
        onSend = { onSend(comment, sticky, notify, persistent) },
    ) {
        CommentField(comment) { comment = it }
        Option(R.string.acknowledge_sticky, sticky, DetailTags.FORM_STICKY) { sticky = it }
        Text(
            text = stringResource(
                if (sticky) R.string.acknowledge_sticky_warning else R.string.acknowledge_sticky_off,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (sticky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(DetailTags.FORM_STICKY_NOTE),
        )
        Option(R.string.acknowledge_notify, notify, DetailTags.FORM_NOTIFY) { notify = it }
        Option(R.string.acknowledge_persistent, persistent, DetailTags.FORM_PERSISTENT) { persistent = it }
    }
}

@Composable
private fun CommentForm(onCancel: () -> Unit, onSend: (String) -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    Form(
        title = R.string.comment_title,
        send = R.string.action_send,
        canSend = storedCommentText(comment).isNotEmpty(),
        onCancel = onCancel,
        onSend = { onSend(comment) },
    ) {
        CommentField(comment) { comment = it }
    }
}

@Composable
private fun Form(
    title: Int,
    send: Int,
    canSend: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        content()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(DetailTags.FORM_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onSend, enabled = canSend, modifier = Modifier.testTag(DetailTags.FORM_SEND)) {
                Text(stringResource(send))
            }
        }
    }
}

/** Removing an acknowledgement can set notifications going, so it is asked about first. */
@Composable
private fun RemoveAcknowledgementDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_acknowledgement_title)) },
        text = { Text(stringResource(R.string.remove_acknowledgement_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(DetailTags.DIALOG_CONFIRM)) {
                Text(stringResource(R.string.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(DetailTags.DIALOG_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * The comment, and beneath it what Nagios will make of it when that differs: Nagios drops
 * some characters without saying so, and the app would rather say so first.
 */
@Composable
private fun CommentField(comment: String, onChange: (String) -> Unit) {
    val stored = storedCommentText(comment)
    // The form was asked for in order to type in it: bring the keyboard up without a second tap.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = comment,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.comment_label)) },
        supportingText = { Text(stringResource(R.string.comment_required)) },
        maxLines = COMMENT_LINES,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(DetailTags.FORM_COMMENT),
    )
    if (stored.isNotEmpty() && stored != comment.trim()) {
        Text(
            text = stringResource(R.string.comment_stored_as, stored),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(DetailTags.FORM_STORED_AS),
        )
    }
}

@Composable
private fun Option(label: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The row is the control; the box only shows its state.
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

private enum class ActionForm { NONE, ACKNOWLEDGE, COMMENT, REMOVE_ACKNOWLEDGEMENT }

private const val COMMENT_LINES = 4
