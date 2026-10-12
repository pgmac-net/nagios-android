// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository

object WidgetConfigTags {
    const val EXPLANATION = "widget_config_explanation"
    const val NO_PROFILES = "widget_config_no_profiles"
    const val ADD_PROFILE = "widget_config_add_profile"

    fun profile(id: Long) = "widget_config_profile_$id"
}

data class WidgetConfigState(val loading: Boolean = true, val profiles: List<Profile> = emptyList())

@HiltViewModel
class WidgetConfigViewModel @Inject constructor(profiles: ProfileRepository) : ViewModel() {
    val state: StateFlow<WidgetConfigState> = profiles.observeProfiles()
        .map { WidgetConfigState(loading = false, profiles = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WidgetConfigState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The list of profiles to choose from. It also says, before anything is chosen, what a widget
 * costs: the profile will be checked in the background, which with notifications off it was not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigContent(
    state: WidgetConfigState,
    onChoose: (Long) -> Unit,
    onAddProfile: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_config_title)) },
                navigationIcon = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.widget_config_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(WidgetConfigTags.EXPLANATION),
            )
            if (!state.loading && state.profiles.isEmpty()) {
                Text(stringResource(R.string.widget_config_no_profiles), Modifier.testTag(WidgetConfigTags.NO_PROFILES))
                Button(onClick = onAddProfile, modifier = Modifier.testTag(WidgetConfigTags.ADD_PROFILE)) {
                    Text(stringResource(R.string.widget_config_open_app))
                }
            }
            state.profiles.forEach { profile ->
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(profile.id) }
                        .padding(vertical = 8.dp).testTag(WidgetConfigTags.profile(profile.id)),
                ) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.widget_config_interval,
                            stringResource(profile.notifications.interval.label()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun PollInterval.label(): Int = when (this) {
    PollInterval.FIFTEEN_MINUTES -> R.string.notify_interval_15
    PollInterval.THIRTY_MINUTES -> R.string.notify_interval_30
    PollInterval.ONE_HOUR -> R.string.notify_interval_60
}
