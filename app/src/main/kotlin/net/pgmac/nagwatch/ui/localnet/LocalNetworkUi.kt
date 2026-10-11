// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.localnet

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.localnet.LOCAL_NETWORK_PERMISSION

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

/**
 * The two things a screen can put in front of the person about local network access: Android's
 * own prompt, and, once Android will no longer show that, the app's page in system settings.
 *
 * Kept apart from the view models, which decide *which*, because both need an Activity.
 */
class LocalNetworkRequest internal constructor(private val prompt: () -> Unit, private val settings: () -> Unit) {
    fun askAndroid() = prompt()

    fun openSettings() = settings()
}

/**
 * Remembers a [LocalNetworkRequest] for this screen. [onAnswer] gets Android's answer, and whether
 * Android would still show the prompt afterwards: it stops after a second refusal, and the
 * only sign of it is `shouldShowRequestPermissionRationale` turning false.
 */
@Composable
fun rememberLocalNetworkRequest(onAnswer: (granted: Boolean, canAskAgain: Boolean) -> Unit): LocalNetworkRequest {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onAnswer)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain = context.activity()
            ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, LOCAL_NETWORK_PERMISSION) } ?: false
        latest.value(granted, canAskAgain)
    }
    return remember(launcher, context) {
        LocalNetworkRequest(
            prompt = { launcher.launch(LOCAL_NETWORK_PERMISSION) },
            settings = { context.openAppSettings() },
        )
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without a settings app has nothing to open. The error text still says what is wrong.
    }
}

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
