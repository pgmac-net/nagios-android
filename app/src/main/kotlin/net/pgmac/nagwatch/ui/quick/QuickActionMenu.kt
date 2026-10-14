// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.quick

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.ui.detail.text

/**
 * A row of a list, with what it does when touched: a tap opens it, and pressing and holding
 * opens the quick actions for it. There is no swipe on a row, on purpose: a sideways drag
 * changes tab, and nothing that changes Nagios should be one stray movement away
 * (docs/adr/0012).
 *
 * [content] is handed the gestures to put on whatever it draws as the row; the menu is anchored
 * to the same place.
 */
@Composable
fun QuickActionRow(
    target: QuickTarget,
    quick: QuickMenu?,
    onOpen: () -> Unit,
    content: @Composable (gestures: Modifier) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Where on the row the finger went down, and how tall the row is: the menu opens there, not
    // at the row's left edge however far along it was pressed (pgmac-net/nagios-android#74).
    var pressedAt by remember { mutableStateOf(Offset.Zero) }
    var rowHeight by remember { mutableStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val gestures = Modifier
        // The row whose menu is open is marked, so it is plain which problem the menu is about.
        .background(if (open) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
        .pointerInput(Unit) {
            // Only looks: the press is left for the click and the long click below.
            awaitEachGesture {
                pressedAt = awaitFirstDown(requireUnconsumed = false, PointerEventPass.Initial).position
            }
        }
        .combinedClickable(
            onClick = onOpen,
            onLongClickLabel = quick?.let { stringResource(R.string.quick_menu) },
            onLongClick = quick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it.actions.opened()
                    open = true
                }
            },
        )
    Box(Modifier.onSizeChanged { rowHeight = it.height }) {
        content(gestures)
        if (quick != null) {
            QuickActionMenu(
                expanded = open,
                offset = with(LocalDensity.current) {
                    // A menu is placed under its anchor, which is the whole row. Moving it up by
                    // what is left of the row below the finger puts its top at the finger.
                    DpOffset(pressedAt.x.toDp(), (pressedAt.y - rowHeight).toDp())
                },
                target = target,
                state = quick.state,
                onDismiss = { open = false },
                onAction = { action ->
                    open = false
                    quick.actions.run(action, target.ref)
                },
                onOpen = {
                    open = false
                    onOpen()
                },
            )
        }
    }
}

/**
 * The actions themselves. One that cannot work is shown disabled with the reason above it, not
 * hidden: a missing item looks like a missing feature. One that makes no sense for this row
 * (acknowledging what is already acknowledged) is left out, as its button is on the detail screen.
 */
@Composable
private fun QuickActionMenu(
    expanded: Boolean,
    offset: DpOffset,
    target: QuickTarget,
    state: QuickUiState,
    onDismiss: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onOpen: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        // It changes Nagios, so it should not be mistaken for part of the list behind it: a
        // surface of its own, an outline in the theme's accent, and a shadow.
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(MENU_BORDER, MaterialTheme.colorScheme.primary),
        shadowElevation = MENU_SHADOW,
        modifier = Modifier.testTag(QuickTags.MENU),
    ) {
        if (state.readOnly) {
            Text(
                text = stringResource(R.string.actions_read_only),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(QuickTags.READ_ONLY),
            )
        }
        if (target.canAcknowledge) Item(QuickAction.ACKNOWLEDGE, R.string.action_acknowledge, state, onAction)
        if (target.canRemoveAcknowledgement) {
            Item(QuickAction.REMOVE_ACKNOWLEDGEMENT, R.string.action_remove_acknowledgement, state, onAction)
        }
        Item(QuickAction.RECHECK, R.string.action_recheck, state, onAction)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open)) },
            onClick = onOpen,
            modifier = Modifier.testTag(QuickTags.OPEN),
        )
    }
}

@Composable
private fun Item(action: QuickAction, label: Int, state: QuickUiState, onAction: (QuickAction) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = { onAction(action) },
        enabled = state.enabled,
        modifier = Modifier.testTag(QuickTags.item(action)),
    )
}

private val MENU_BORDER = 1.5.dp
private val MENU_SHADOW = 12.dp

/**
 * Says what became of the last quick action, at the bottom of the screen: on its way, done, or
 * why not. An acknowledgement that Nagios has confirmed can be taken back from here.
 *
 * It waits on screen while a command is on its way and for a while after it settles, and is
 * replaced when the next piece of news arrives.
 */
@Composable
fun QuickFeedback(quick: QuickMenu, snackbars: SnackbarHostState) {
    val progress = quick.state.progress ?: return
    val message = progress.quickText()
    val undo = stringResource(R.string.action_undo)
    LaunchedEffect(progress) {
        val canUndo = progress is ActionProgress.Confirmed && progress.command is Command.Acknowledge
        val result = snackbars.showSnackbar(
            message = message,
            actionLabel = undo.takeIf { canUndo },
            withDismissAction = !progress.inFlight,
            duration = if (progress.inFlight) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> quick.actions.undo()
            SnackbarResult.Dismissed -> quick.actions.dismiss(progress)
        }
    }
}

/**
 * The detail screen's words, but for one case. There, an acknowledged problem stays where it is.
 * In a list it moves: it goes to the handled problems, which Hosts and Services hide until asked
 * for. Saying "acknowledged" and letting the row vanish would look like something had gone wrong.
 */
@Composable
private fun ActionProgress.quickText(): String = when {
    this is ActionProgress.Confirmed && command is Command.Acknowledge -> stringResource(R.string.quick_acknowledged)
    else -> text()
}
