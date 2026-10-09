// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.pgmac.nagwatch.AppInfo
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository

object ProfilesTags {
    const val EMPTY = "profiles_empty"
    const val ADD = "profiles_add"
    const val CLEARTEXT_MARKER = "profiles_cleartext_marker"
}

data class ProfilesState(
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val version: String = "",
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(repository: ProfileRepository, appInfo: AppInfo) : ViewModel() {
    val state: StateFlow<ProfilesState> = repository.observeProfiles()
        .map { ProfilesState(loading = false, profiles = it, version = appInfo.versionName) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfilesState())

    private companion object {
        /** Survives a rotation without restarting the query. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ProfilesScreen(onAdd: () -> Unit, onEdit: (Long) -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfilesContent(state, onAdd, onEdit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesContent(state: ProfilesState, onAdd: () -> Unit, onEdit: (Long) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.profiles_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.testTag(ProfilesTags.ADD)) {
                Text(stringResource(R.string.profiles_add))
            }
        },
    ) { innerPadding ->
        when {
            state.loading -> Unit

            state.profiles.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp).testTag(ProfilesTags.EMPTY),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.profiles_empty_text), textAlign = TextAlign.Center)
                Text(
                    text = stringResource(R.string.app_version, state.version),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                items(state.profiles, key = Profile::id) { profile ->
                    ProfileRow(profile, onClick = { onEdit(profile.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium)
        // Kept visible on purpose: an unencrypted profile should never be forgotten about.
        if (profile.isCleartext) {
            Text(
                text = stringResource(R.string.profiles_cleartext_marker),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(ProfilesTags.CLEARTEXT_MARKER),
            )
        }
    }
}
