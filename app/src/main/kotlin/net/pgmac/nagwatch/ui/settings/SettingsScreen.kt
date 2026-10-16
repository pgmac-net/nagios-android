// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.notify.EmojiSet
import net.pgmac.nagwatch.notify.NotifierSettings
import net.pgmac.nagwatch.ui.NagwatchTopAppBar

object SettingsTags {
    const val LARGE_ICON = "settings_large_icon"

    fun emojiSet(set: EmojiSet) = "settings_emoji_${set.id}"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: NotifierSettings) : ViewModel() {
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val chosen by viewModel.emojiSet.collectAsStateWithLifecycle()
    val largeIcon by viewModel.largeIcon.collectAsStateWithLifecycle()
    SettingsContent(
        chosen = chosen,
        onChoose = viewModel::choose,
        largeIcon = largeIcon,
        onLargeIcon = viewModel::chooseLargeIcon,
        resources = LocalResources.current,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    chosen: EmojiSet,
    onChoose: (EmojiSet) -> Unit,
    largeIcon: Boolean,
    onLargeIcon: (Boolean) -> Unit,
    resources: Resources,
    modifier: Modifier = Modifier,
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
        }
    }
}
