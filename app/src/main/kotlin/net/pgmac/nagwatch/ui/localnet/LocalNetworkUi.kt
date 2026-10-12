// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.localnet

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.localnet.LOCAL_NETWORK_PERMISSION
import net.pgmac.nagwatch.ui.PermissionRequest
import net.pgmac.nagwatch.ui.appDetailsSettings
import net.pgmac.nagwatch.ui.rememberPermissionRequest

object LocalNetworkTags {
    const val EXPLANATION = "local_network_explanation"
    const val CONTINUE = "local_network_continue"
    const val NOT_NOW = "local_network_not_now"
    const val ALLOW = "local_network_allow"
}

/** What a screen that can meet Android's local network protection can be asked to do. */
interface LocalNetworkActions {
    /** "Continue" on the app's explanation: now Android's prompt. */
    fun localNetworkContinue()

    /** "Not now" on the app's explanation: carry on without asking. */
    fun localNetworkNotNow()

    /** The button on an error that is probably Android's protection. */
    fun allowLocalNetwork()
}

/** A [PermissionRequest] for local network access. */
typealias LocalNetworkRequest = PermissionRequest

/** Remembers the request for this screen; see [rememberPermissionRequest] for what [onAnswer] is told. */
@Composable
fun rememberLocalNetworkRequest(onAnswer: (granted: Boolean, canAskAgain: Boolean) -> Unit): LocalNetworkRequest =
    rememberPermissionRequest(LOCAL_NETWORK_PERMISSION, ::appDetailsSettings, onAnswer)

/**
 * The app's own sentence, shown once before Android's prompt. "Not now" is here so that someone
 * can decline without spending one of the two refusals Android allows.
 */
@Composable
fun LocalNetworkExplanation(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                stringResource(R.string.local_network_title),
                modifier = Modifier.testTag(LocalNetworkTags.EXPLANATION),
            )
        },
        text = { Text(stringResource(R.string.local_network_text)) },
        confirmButton = {
            TextButton(onClick = onContinue, modifier = Modifier.testTag(LocalNetworkTags.CONTINUE)) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(LocalNetworkTags.NOT_NOW)) {
                Text(stringResource(R.string.local_network_not_now))
            }
        },
    )
}

/** Beside an error that is probably Android's protection: the way to grant it. */
@Composable
fun AllowLocalNetworkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.testTag(LocalNetworkTags.ALLOW)) {
        Text(stringResource(R.string.action_allow_local_network))
    }
}
