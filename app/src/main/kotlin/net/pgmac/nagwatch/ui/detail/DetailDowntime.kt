// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.command.storedCommentText
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.status.ActionProgress

/**
 * Schedules a fixed downtime: a comment, and either a length starting now or a start and an
 * end. The window is shown twice before it is sent: in the phone's time, and as the server
 * will be told it, so a surprise about timezones is had before the downtime exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DowntimeForm(state: DetailUiState, onCancel: () -> Unit, onSend: (String, DowntimeWindow) -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf(DEFAULT_PRESET) }
    var customStart by rememberSaveable { mutableStateOf(0L) }
    var customEnd by rememberSaveable { mutableStateOf(0L) }
    var picking by rememberSaveable { mutableStateOf(Picking.NONE) }

    val window = when (preset) {
        CUSTOM -> DowntimeWindow.Between(Instant.ofEpochMilli(customStart), Instant.ofEpochMilli(customEnd))
        else -> DowntimeWindow.FromNow(PRESETS[preset])
    }
    val valid = window.isValid(state.now)
    ActionFormLayout(
        title = R.string.downtime_title,
        send = R.string.action_schedule,
        canSend = valid && storedCommentText(comment).isNotEmpty(),
        onCancel = onCancel,
        onSend = { onSend(comment, window) },
    ) {
        CommentField(comment) { comment = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEachIndexed { index, length ->
                FilterChip(
                    selected = preset == index,
                    onClick = { preset = index },
                    label = { Text(presetLabel(length)) },
                    modifier = Modifier.testTag(DetailTags.downtimePreset(length.toMinutes())),
                )
            }
            FilterChip(
                selected = preset == CUSTOM,
                onClick = {
                    if (preset != CUSTOM) {
                        // Start from something sensible: now, to the minute, for the usual two hours.
                        val start = state.now.truncatedTo(ChronoUnit.MINUTES)
                        customStart = start.toEpochMilli()
                        customEnd = (start + PRESETS[DEFAULT_PRESET]).toEpochMilli()
                    }
                    preset = CUSTOM
                },
                label = { Text(stringResource(R.string.downtime_custom)) },
                modifier = Modifier.testTag(DetailTags.DOWNTIME_CUSTOM),
            )
        }
        if (preset == CUSTOM) {
            Moment(R.string.downtime_starts, Instant.ofEpochMilli(customStart), DetailTags.DOWNTIME_START) {
                picking = Picking.START
            }
            Moment(R.string.downtime_ends, Instant.ofEpochMilli(customEnd), DetailTags.DOWNTIME_END) {
                picking = Picking.END
            }
        }
        WindowPreview(window, state.now, state.serverClock, valid)
    }
    when (picking) {
        Picking.NONE -> Unit

        Picking.START -> MomentPicker(Instant.ofEpochMilli(customStart), { picking = Picking.NONE }) { picked ->
            // Moving the start keeps the length, so the end does not end up before it.
            customEnd = picked.toEpochMilli() + (customEnd - customStart)
            customStart = picked.toEpochMilli()
            picking = Picking.NONE
        }

        Picking.END -> MomentPicker(Instant.ofEpochMilli(customEnd), { picking = Picking.NONE }) { picked ->
            customEnd = picked.toEpochMilli()
            picking = Picking.NONE
        }
    }
}

/** The window in the phone's time, and beneath it as the server will be told it. */
@Composable
private fun WindowPreview(window: DowntimeWindow, now: Instant, clock: ServerClock?, valid: Boolean) {
    val (start, end) = window.resolve(now)
    val local = when (window) {
        is DowntimeWindow.FromNow -> stringResource(R.string.downtime_window_from_now, formatWhen(end))

        is DowntimeWindow.Between ->
            stringResource(R.string.downtime_window_between, formatWhen(start), formatWhen(end))
    }
    Text(local, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(DetailTags.DOWNTIME_WINDOW))
    if (!valid) {
        Text(
            text = stringResource(R.string.downtime_window_invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(DetailTags.DOWNTIME_INVALID),
        )
    }
    val serverStart = clock?.format(start)
    val serverEnd = clock?.format(end)
    // Left out when it cannot be written without guessing the date order; the app asks before sending.
    if (clock != null && serverStart != null && serverEnd != null) {
        Text(
            text = stringResource(R.string.downtime_window_server, serverStart, serverEnd, clock.offset.label()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(DetailTags.DOWNTIME_WINDOW_SERVER),
        )
    }
}

@Composable
private fun presetLabel(length: Duration): String = when {
    length < Duration.ofHours(1) -> stringResource(R.string.downtime_minutes, length.toMinutes().toInt())
    else -> stringResource(R.string.downtime_hours, length.toHours().toInt())
}

private fun ZoneOffset.label(): String = if (this == ZoneOffset.UTC) "UTC" else "UTC$id"

@Composable
private fun Moment(label: Int, moment: Instant, tag: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_fact, stringResource(label), formatWhen(moment)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange, modifier = Modifier.testTag(tag)) {
            Text(stringResource(R.string.action_change))
        }
    }
}

/** A date, then a time, in the phone's own timezone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentPicker(initial: Instant, onDismiss: () -> Unit, onPicked: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = initial.atZone(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val chosen = date
    if (chosen == null) {
        // The date picker speaks UTC midnights whatever the phone's zone is.
        val dates = rememberDatePickerState(
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        dates.selectedDateMillis?.let { picked ->
                            date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                    },
                ) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            // No typed entry: only the calendar, so there is no second date format to get wrong.
            DatePicker(state = dates, showModeToggle = false)
        }
    } else {
        val times = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { onPicked(chosen.atTime(times.hour, times.minute).atZone(zone).toInstant()) },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = times) },
        )
    }
}

/**
 * Asked only when a downtime cannot be written without it: on a day whose number equals its
 * month, a server that has never been seen on any other day. Nothing has been sent.
 */
@Composable
internal fun DateOrderDialog(needed: ActionProgress.DateOrderNeeded, actions: DetailActions) {
    AlertDialog(
        onDismissRequest = actions::dismissDateOrder,
        title = { Text(stringResource(R.string.date_order_title)) },
        text = { Text(stringResource(R.string.date_order_text, needed.example)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { actions.answerDateOrder(ServerDateFormat.US) },
                    modifier = Modifier.testTag(DetailTags.DATE_ORDER_MONTH_FIRST),
                ) { Text(stringResource(R.string.date_order_month_first)) }
                TextButton(
                    onClick = { actions.answerDateOrder(ServerDateFormat.EURO) },
                    modifier = Modifier.testTag(DetailTags.DATE_ORDER_DAY_FIRST),
                ) { Text(stringResource(R.string.date_order_day_first)) }
                TextButton(
                    onClick = actions::dismissDateOrder,
                    modifier = Modifier.testTag(DetailTags.DIALOG_CANCEL),
                ) { Text(stringResource(R.string.date_order_neither)) }
            }
        },
    )
}

/** Cancelling a downtime lets notifications go again, so it is asked about first. */
@Composable
internal fun CancelDowntimeDialog(downtime: Downtime, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cancel_downtime_title)) },
        text = {
            Text(
                stringResource(
                    R.string.cancel_downtime_text,
                    formatWhen(downtime.start),
                    formatWhen(downtime.end),
                    downtime.comment,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(DetailTags.DIALOG_CONFIRM)) {
                Text(stringResource(R.string.action_cancel_downtime))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(DetailTags.DIALOG_CANCEL)) {
                Text(stringResource(R.string.action_keep))
            }
        },
    )
}

private enum class Picking { NONE, START, END }

/** The lengths offered. The third, two hours, is what Nagios' own form starts with. */
internal val PRESETS: List<Duration> = listOf(30L, 60L, 120L, 240L, 480L, 1440L).map(Duration::ofMinutes)
private const val DEFAULT_PRESET = 2
private const val CUSTOM = -1
