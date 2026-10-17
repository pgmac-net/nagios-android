// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.localnet.AllowLocalNetworkButton

@Composable
internal fun Loading() {
    Text(stringResource(R.string.problems_loading), modifier = Modifier.padding(24.dp).testTag(HomeTags.LOADING))
}

/** First run: nothing to show until a profile exists, so say what to do. */
@Composable
internal fun NoProfiles(actions: HomeActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(HomeTags.NO_PROFILES),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.profiles_empty_text))
        Button(onClick = actions::addProfile, modifier = Modifier.testTag(HomeTags.ADD_PROFILE)) {
            Text(stringResource(R.string.profiles_add))
        }
    }
}

/** The first fetch failed, so there is no earlier result to fall back on. */
@Composable
internal fun FirstFailure(error: StatusError?, profile: Profile, actions: HomeActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(HomeTags.ERROR),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.problems_failed_title), style = MaterialTheme.typography.titleLarge)
        Text(error.message())
        if (error is StatusError.Nagios && error.error == NagiosError.LocalNetworkBlocked) {
            AllowLocalNetworkButton(onClick = actions::allowLocalNetwork)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions::refresh) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = { actions.editProfile(profile.id) }) {
                Text(stringResource(R.string.problems_edit_profile))
            }
        }
    }
}
