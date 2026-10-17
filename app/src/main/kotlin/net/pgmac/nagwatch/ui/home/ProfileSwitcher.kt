// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import net.pgmac.nagwatch.R

@Composable
internal fun ProfileSwitcher(state: HomeUiState, actions: HomeActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(HomeTags.PROFILE_MENU)) {
            Text(
                text = state.selected?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        open = false
                        actions.selectProfile(profile.id)
                    },
                )
            }
            HorizontalDivider()
            state.selected?.let { selected ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.problems_edit_profile)) },
                    onClick = {
                        open = false
                        actions.editProfile(selected.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.problems_manage_profiles)) },
                onClick = {
                    open = false
                    actions.manageProfiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.problems_settings)) },
                onClick = {
                    open = false
                    actions.openSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.problems_about)) },
                modifier = Modifier.testTag(HomeTags.ABOUT),
                onClick = {
                    open = false
                    actions.openAbout()
                },
            )
        }
    }
}
