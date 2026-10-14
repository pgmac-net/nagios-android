// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.widget.OPAQUE
import net.pgmac.nagwatch.widget.WidgetBackground

object WidgetConfigTags {
    const val EXPLANATION = "widget_config_explanation"
    const val NO_PROFILES = "widget_config_no_profiles"
    const val ADD_PROFILE = "widget_config_add_profile"
    const val OPACITY = "widget_config_opacity"
    const val OPACITY_LABEL = "widget_config_opacity_label"
    const val PREVIEW = "widget_config_preview"
    const val SAVE = "widget_config_save"

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

/** What a widget is set to: its profile, if it has one yet, and how opaque its background is. */
data class WidgetSetup(
    val profileId: Long? = null,
    val opacity: Int = OPAQUE,
    /** True when reconfiguring a widget already on the home screen, false when placing one. */
    val placed: Boolean = false,
)

/**
 * The widget's setup: which profile it shows and how see-through it is. It also says, before
 * anything is chosen, what a widget costs: the profile will be checked in the background, which
 * with notifications off it was not.
 *
 * Nothing is kept until the button: choosing a profile only selects it, so the background can be
 * set in the same visit (pgmac-net/nagios-android#65).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigContent(
    state: WidgetConfigState,
    current: WidgetSetup,
    onSave: (profileId: Long, opacity: Int) -> Unit,
    onAddProfile: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable(current) { mutableStateOf(current.profileId) }
    var opacity by rememberSaveable(current) { mutableIntStateOf(current.opacity) }
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
                return@Column
            }
            state.profiles.forEach { profile ->
                HorizontalDivider()
                ProfileChoice(profile, selected = profile.id == chosen) { chosen = profile.id }
            }
            HorizontalDivider()
            BackgroundChoice(opacity) { opacity = it }
            Button(
                onClick = { chosen?.let { onSave(it, opacity) } },
                enabled = chosen != null,
                modifier = Modifier.fillMaxWidth().testTag(WidgetConfigTags.SAVE),
            ) {
                Text(stringResource(if (current.placed) R.string.widget_config_save else R.string.widget_config_add))
            }
        }
    }
}

@Composable
private fun ProfileChoice(profile: Profile, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp)
            .testTag(WidgetConfigTags.profile(profile.id)),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.widget_config_interval, stringResource(profile.notifications.interval.label())),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The slider, and what the widget would look like with it, over something that shows the see-through. */
@Composable
private fun BackgroundChoice(opacity: Int, onChange: (Int) -> Unit) {
    Text(
        stringResource(R.string.widget_config_background, opacity),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(WidgetConfigTags.OPACITY_LABEL),
    )
    Slider(
        value = opacity.toFloat(),
        onValueChange = { onChange((it / STEP).roundToInt() * STEP) },
        valueRange = 0f..OPAQUE.toFloat(),
        steps = OPAQUE / STEP - 1,
        modifier = Modifier.testTag(WidgetConfigTags.OPACITY),
    )
    Text(stringResource(R.string.widget_config_background_hint), style = MaterialTheme.typography.bodySmall)
    Preview(opacity)
}

/**
 * Not the widget itself, which a phone screen cannot draw, but its background by the same rule
 * ([WidgetBackground]), washed amber as for a warning, over stripes standing in for a wallpaper.
 */
@Composable
private fun Preview(opacity: Int) {
    val stripeA = MaterialTheme.colorScheme.surfaceVariant
    val stripeB = MaterialTheme.colorScheme.outlineVariant
    val background = WidgetBackground.surface(
        base = MaterialTheme.colorScheme.surfaceContainerHigh,
        state = NagwatchTheme.stateColors.warning,
        chosen = opacity,
        demoted = false,
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clip(RoundedCornerShape(12.dp))
            .drawBehind {
                val band = STRIPE.toPx()
                var x = -size.height
                var i = 0
                while (x < size.width) {
                    drawLine(
                        if (i++ % 2 ==
                            0
                        ) {
                            stripeA
                        } else {
                            stripeB
                        },
                        Offset(x, size.height),
                        Offset(x + size.height, 0f),
                        band,
                    )
                    x += band
                }
            }
            .padding(16.dp)
            .testTag(WidgetConfigTags.PREVIEW),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.widget_config_preview_name), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.widget_config_preview_line),
                style = MaterialTheme.typography.bodySmall,
                color = NagwatchTheme.stateColors.warning,
            )
        }
    }
}

private const val STEP = 5
private val PREVIEW_HEIGHT = 120.dp
private val STRIPE = 14.dp

private fun PollInterval.label(): Int = when (this) {
    PollInterval.FIFTEEN_MINUTES -> R.string.notify_interval_15
    PollInterval.THIRTY_MINUTES -> R.string.notify_interval_30
    PollInterval.ONE_HOUR -> R.string.notify_interval_60
}
