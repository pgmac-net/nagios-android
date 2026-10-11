// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/**
 * The two things a screen can put in front of the person about a permission: Android's own
 * prompt, and, once Android will no longer show that, a page in system settings.
 *
 * Kept apart from the view models, which decide *which*, because both need an Activity.
 */
class PermissionRequest internal constructor(private val prompt: () -> Unit, private val settings: () -> Unit) {
    fun askAndroid() = prompt()

    fun openSettings() = settings()
}

/**
 * Remembers a [PermissionRequest] for this screen. [onAnswer] gets Android's answer, and whether
 * Android would still show the prompt afterwards: it stops after a second refusal, and the
 * only sign of it is `shouldShowRequestPermissionRationale` turning false.
 *
 * @param settings the page to open when the prompt is no longer available.
 */
@Composable
fun rememberPermissionRequest(
    permission: String,
    settings: (Context) -> Intent,
    onAnswer: (granted: Boolean, canAskAgain: Boolean) -> Unit,
): PermissionRequest {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onAnswer)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAskAgain = context.activity()
            ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false
        latest.value(granted, canAskAgain)
    }
    return remember(launcher, context, permission) {
        PermissionRequest(
            prompt = { launcher.launch(permission) },
            settings = { context.open(settings(context)) },
        )
    }
}

/** The app's own page in system settings, where every permission can be changed. */
fun appDetailsSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** The app's notification page in system settings. */
fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A phone without a settings app has nothing to open. The text on screen still says what is wrong.
    }
}
