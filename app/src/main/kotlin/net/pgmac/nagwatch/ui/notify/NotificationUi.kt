// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.notify.NOTIFICATION_PERMISSION
import net.pgmac.nagwatch.notify.NotifyChannel
import net.pgmac.nagwatch.notify.canScheduleExactAlarms
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.ui.PermissionRequest
import net.pgmac.nagwatch.ui.appNotificationSettings
import net.pgmac.nagwatch.ui.openAlarmPermissionSettings
import net.pgmac.nagwatch.ui.openAppNotificationSettings
import net.pgmac.nagwatch.ui.rememberPermissionRequest

object NotificationTags {
    const val SWITCH = "notify_switch"
    const val REFUSED = "notify_refused"
    const val ALLOW = "notify_allow"
    const val UNHANDLED_ONLY = "notify_unhandled_only"
    const val UNREACHABLE = "notify_unreachable"
    const val SOUNDS = "notify_sounds"
    const val LOUD_ALERTS = "notify_loud_alerts"
    const val LOUD_ALERTS_BLOCKED = "notify_loud_alerts_blocked"
    const val ALLOW_ALARMS = "notify_allow_alarms"
    const val OFFER = "notify_offer"
    const val OFFER_TURN_ON = "notify_offer_turn_on"
    const val OFFER_NOT_NOW = "notify_offer_not_now"

    fun interval(interval: PollInterval) = "notify_interval_${interval.name}"
}

/** Remembers the request for this screen; see [rememberPermissionRequest] for what [onAnswer] is told. */
@Composable
fun rememberNotificationRequest(onAnswer: (granted: Boolean, canAskAgain: Boolean) -> Unit): PermissionRequest =
    rememberPermissionRequest(NOTIFICATION_PERMISSION, ::appNotificationSettings, onAnswer)

/** What the "Notifications" section of the profile editor can ask for. */
interface NotificationFieldActions {
    /**
     * The main switch. Turning it on may have to ask Android first, so it is not a plain edit.
     * The button shown after a refusal asks for the same thing: on.
     */
    fun setNotifications(enabled: Boolean)

    fun editNotifications(change: (NotificationSettings) -> NotificationSettings)
}

/**
 * The "Background checks" section of the profile editor: how often this profile is polled while
 * the app is closed.
 *
 * It is not part of "Notifications" and is always shown, because notifications are not the only
 * reason a profile is polled: a widget is another. An interval hidden behind a switch that is
 * off would be one nobody could see or change.
 */
@Composable
fun BackgroundCheckFields(interval: PollInterval, onChange: (PollInterval) -> Unit) {
    Text(stringResource(R.string.background_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.notify_interval), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PollInterval.entries.forEach { choice ->
            FilterChip(
                selected = interval == choice,
                onClick = { onChange(choice) },
                label = { Text(stringResource(choice.label())) },
                modifier = Modifier.testTag(NotificationTags.interval(choice)),
            )
        }
    }
    Text(stringResource(R.string.background_hint), style = MaterialTheme.typography.bodySmall)
}

/**
 * The "Notifications" section of the profile editor. The choices below the switch are shown
 * only while it is on: with it off they decide nothing.
 */
@Composable
fun NotificationFields(settings: NotificationSettings, refused: Boolean, actions: NotificationFieldActions) {
    Text(stringResource(R.string.notify_title), style = MaterialTheme.typography.titleMedium)
    LabelledSwitch(
        label = R.string.notify_switch,
        checked = settings.enabled,
        tag = NotificationTags.SWITCH,
        onChange = actions::setNotifications,
    )
    Text(stringResource(R.string.notify_hint), style = MaterialTheme.typography.bodySmall)
    if (refused) {
        Text(
            // On and refused together is "allowed once, blocked since": the checks go on, the news does not.
            text = stringResource(if (settings.enabled) R.string.notify_blocked else R.string.notify_refused),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(NotificationTags.REFUSED),
        )
        Button(onClick = { actions.setNotifications(true) }, modifier = Modifier.testTag(NotificationTags.ALLOW)) {
            Text(stringResource(R.string.action_allow_notifications))
        }
    }
    if (!settings.enabled) return

    LabelledSwitch(
        label = R.string.notify_unhandled_only,
        checked = settings.unhandledOnly,
        tag = NotificationTags.UNHANDLED_ONLY,
    ) { checked -> actions.editNotifications { it.copy(unhandledOnly = checked) } }
    Text(stringResource(R.string.notify_unhandled_only_hint), style = MaterialTheme.typography.bodySmall)
    LabelledSwitch(
        label = R.string.notify_unreachable,
        checked = settings.tellUnreachable,
        tag = NotificationTags.UNREACHABLE,
    ) { checked -> actions.editNotifications { it.copy(tellUnreachable = checked) } }
    Text(stringResource(R.string.notify_unreachable_hint), style = MaterialTheme.typography.bodySmall)
    SoundsLink()
    LoudAlertFields(settings.loudAlerts) { checked -> actions.editNotifications { it.copy(loudAlerts = checked) } }
}

/**
 * Android keeps the sound, importance and vibration of each kind of notification, and lets the
 * person set each from its own list of sounds. This is the way there. The kinds are the app's,
 * not a profile's, so what is set there applies to every profile.
 */
@Composable
private fun SoundsLink() {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            // Until something has been posted there are no channels, and the page would be empty.
            NotifyChannel.createAll(context)
            openAppNotificationSettings(context)
        },
        modifier = Modifier.testTag(NotificationTags.SOUNDS),
    ) {
        Text(stringResource(R.string.notify_sounds))
    }
    Text(stringResource(R.string.notify_sounds_hint), style = MaterialTheme.typography.bodySmall)
}

/**
 * The loud alerts switch, and what it needs from Android: the "Alarms & reminders" permission.
 * Switching on without it goes to Android's page for it. With the switch on and the permission
 * missing (refused there, or taken back later) the section says so, and offers the page again.
 * It is looked at again each time the screen comes back, which is how a visit to that page is seen.
 */
@Composable
private fun LoudAlertFields(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = canScheduleExactAlarms(context)
        onPauseOrDispose {}
    }
    LabelledSwitch(label = R.string.notify_loud_alerts, checked = on, tag = NotificationTags.LOUD_ALERTS) { checked ->
        onChange(checked)
        if (checked && !allowed) openAlarmPermissionSettings(context)
    }
    Text(stringResource(R.string.notify_loud_alerts_hint), style = MaterialTheme.typography.bodySmall)
    if (on && !allowed) {
        Text(
            text = stringResource(R.string.notify_loud_alerts_blocked),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(NotificationTags.LOUD_ALERTS_BLOCKED),
        )
        Button(
            onClick = { openAlarmPermissionSettings(context) },
            modifier = Modifier.testTag(NotificationTags.ALLOW_ALARMS),
        ) {
            Text(stringResource(R.string.action_allow_alarms))
        }
    }
}

@Composable
private fun LabelledSwitch(label: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Text(stringResource(label))
    }
}

internal fun PollInterval.label(): Int = when (this) {
    PollInterval.FIFTEEN_MINUTES -> R.string.notify_interval_15
    PollInterval.THIRTY_MINUTES -> R.string.notify_interval_30
    PollInterval.ONE_HOUR -> R.string.notify_interval_60
}

/**
 * The offer of notifications, made once for a profile after it has loaded. It lives beside the
 * home screen and not in it: whether to offer is nothing to do with what the tabs show.
 */
@Composable
fun NotificationOffer(viewModel: NotificationOfferViewModel = hiltViewModel()) {
    val profile by viewModel.offerFor.collectAsStateWithLifecycle()
    val request = rememberNotificationRequest(viewModel::answered)
    profile?.let { offered ->
        NotificationOfferDialog(
            profileName = offered.name,
            onTurnOn = { viewModel.accept(request) },
            onNotNow = viewModel::decline,
        )
    }
}

@Composable
fun NotificationOfferDialog(profileName: String, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(stringResource(R.string.notify_offer_title), modifier = Modifier.testTag(NotificationTags.OFFER))
        },
        text = { Text(stringResource(R.string.notify_offer_text, profileName)) },
        confirmButton = {
            TextButton(onClick = onTurnOn, modifier = Modifier.testTag(NotificationTags.OFFER_TURN_ON)) {
                Text(stringResource(R.string.notify_offer_turn_on))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(NotificationTags.OFFER_NOT_NOW)) {
                Text(stringResource(R.string.local_network_not_now))
            }
        },
    )
}
