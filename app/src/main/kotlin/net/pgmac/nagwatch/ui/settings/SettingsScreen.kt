// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.notify.AlarmVolume
import net.pgmac.nagwatch.notify.EmojiSet
import net.pgmac.nagwatch.notify.LoudAlert
import net.pgmac.nagwatch.notify.NotifierSettings
import net.pgmac.nagwatch.notify.canScheduleExactAlarms
import net.pgmac.nagwatch.ui.NagwatchTopAppBar
import net.pgmac.nagwatch.ui.openAlarmPermissionSettings

object SettingsTags {
    const val LARGE_ICON = "settings_large_icon"
    const val LOUD_ALERT_VOLUME = "settings_loud_alert_volume"
    const val LOUD_ALERT_TEST = "settings_loud_alert_test"
    const val LOUD_ALERT_BLOCKED = "settings_loud_alert_blocked"

    fun emojiSet(set: EmojiSet) = "settings_emoji_${set.id}"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: NotifierSettings, private val loudAlert: LoudAlert) :
    ViewModel() {
    val emojiSet: StateFlow<EmojiSet> = settings.emojiSet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EmojiSet.DEFAULT)

    val largeIcon: StateFlow<Boolean> = settings.largeIcon
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    fun choose(set: EmojiSet) {
        viewModelScope.launch { settings.setEmojiSet(set) }
    }

    fun chooseLargeIcon(on: Boolean) {
        viewModelScope.launch { settings.setLargeIcon(on) }
    }

    val loudAlertVolume: StateFlow<Int> = settings.loudAlertVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlarmVolume.DEFAULT)

    val sounding: StateFlow<Boolean> = loudAlert.sounding

    fun chooseLoudAlertVolume(percent: Int) {
        // Saved first, so that a test straight after is at the new volume.
        viewModelScope.launch { settings.setLoudAlertVolume(percent) }
    }

    /** The real thing, by the same way a poll would: the person hears what they would hear. */
    fun testLoudAlert() {
        viewModelScope.launch(Dispatchers.IO) { loudAlert.sound() }
    }

    fun stopLoudAlert() = loudAlert.stop()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val chosen by viewModel.emojiSet.collectAsStateWithLifecycle()
    val largeIcon by viewModel.largeIcon.collectAsStateWithLifecycle()
    val volume by viewModel.loudAlertVolume.collectAsStateWithLifecycle()
    val sounding by viewModel.sounding.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = canScheduleExactAlarms(context)
        onPauseOrDispose {}
    }
    SettingsContent(
        chosen = chosen,
        onChoose = viewModel::choose,
        largeIcon = largeIcon,
        onLargeIcon = viewModel::chooseLargeIcon,
        resources = LocalResources.current,
        loudAlert = LoudAlertSection(
            volume = volume,
            sounding = sounding,
            allowed = allowed,
            onVolume = viewModel::chooseLoudAlertVolume,
            onTest = viewModel::testLoudAlert,
            onStop = viewModel::stopLoudAlert,
            onAllow = { openAlarmPermissionSettings(context) },
        ),
    )
}

/** What the loud alert part of Settings shows, and what it can ask for. */
data class LoudAlertSection(
    val volume: Int = AlarmVolume.DEFAULT,
    val sounding: Boolean = false,
    /** Android lets the app use alarms, which a loud alert from the background needs. */
    val allowed: Boolean = true,
    val onVolume: (Int) -> Unit = {},
    val onTest: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onAllow: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    chosen: EmojiSet,
    onChoose: (EmojiSet) -> Unit,
    largeIcon: Boolean,
    onLargeIcon: (Boolean) -> Unit,
    resources: Resources,
    modifier: Modifier = Modifier,
    loudAlert: LoudAlertSection = LoudAlertSection(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { NagwatchTopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.settings_notifications),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(stringResource(R.string.settings_emoji_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_emoji_text), style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.selectableGroup().padding(top = 8.dp)) {
                EmojiSet.entries.forEach { set ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = set == chosen, role = Role.RadioButton, onClick = { onChoose(set) })
                            .padding(vertical = 8.dp)
                            .testTag(SettingsTags.emojiSet(set)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is what is pressed, so the button itself takes no press of its own.
                        RadioButton(selected = set == chosen, onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(stringResource(set.label), style = MaterialTheme.typography.bodyLarge)
                            val preview = set.table(resources).preview.trim()
                            if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_large_icon_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.settings_large_icon_text), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = largeIcon,
                    onCheckedChange = onLargeIcon,
                    modifier = Modifier.testTag(SettingsTags.LARGE_ICON),
                )
            }
            LoudAlertFields(loudAlert)
        }
    }
}

/**
 * The volume, and a way to hear it. The slider saves when it is let go, not at every step of a
 * drag. Test plays the real alert, so a level that will wake the person can be found by ear.
 */
@Composable
private fun LoudAlertFields(section: LoudAlertSection) {
    // Follows the saved value, and the finger while it is down.
    var dragging by remember(section.volume) { mutableFloatStateOf(section.volume.toFloat()) }
    Text(stringResource(R.string.settings_loud_alert_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.settings_loud_alert_text), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { section.onVolume(dragging.roundToInt()) },
            valueRange = AlarmVolume.LOWEST.toFloat()..AlarmVolume.HIGHEST.toFloat(),
            modifier = Modifier.weight(1f).testTag(SettingsTags.LOUD_ALERT_VOLUME),
        )
        Text(
            stringResource(R.string.settings_loud_alert_volume, dragging.roundToInt()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    OutlinedButton(
        onClick = if (section.sounding) section.onStop else section.onTest,
        modifier = Modifier.testTag(SettingsTags.LOUD_ALERT_TEST),
    ) {
        Text(stringResource(if (section.sounding) R.string.action_stop else R.string.settings_loud_alert_test))
    }
    if (!section.allowed) {
        Text(
            stringResource(R.string.settings_loud_alert_blocked),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).testTag(SettingsTags.LOUD_ALERT_BLOCKED),
        )
        Button(onClick = section.onAllow) { Text(stringResource(R.string.action_allow_alarms)) }
    }
    Spacer(Modifier.height(16.dp))
}
