// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.pgmac.nagwatch.MainActivity
import net.pgmac.nagwatch.nagios.model.ObjectRef

/**
 * What a tap on a notification opens: a host, a service, or (with no [ref]) the profile's
 * Problems list.
 *
 * It travels in the intent that starts the app. The intent names the app's own activity
 * explicitly, so nothing outside the app can be started by it, and what comes back out is
 * only ever a profile id and two names to look up.
 */
data class NotificationTarget(val profileId: Long, val ref: ObjectRef? = null) {
    fun toIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        // Reuses the open app instead of stacking a second copy on top of it.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Android treats two pending intents as one unless something it compares differs: this does.
        .setData(
            Uri.Builder().scheme(SCHEME).authority(profileId.toString())
                .appendQueryParameter(EXTRA_HOST, ref?.hostName.orEmpty())
                .appendQueryParameter(EXTRA_SERVICE, ref?.description.orEmpty())
                .build(),
        )
        .putExtra(EXTRA_PROFILE, profileId)
        .putExtra(EXTRA_HOST, ref?.hostName)
        .putExtra(EXTRA_SERVICE, ref?.description)

    companion object {
        private const val SCHEME = "nagwatch"
        private const val EXTRA_PROFILE = "net.pgmac.nagwatch.notify.PROFILE"
        private const val EXTRA_HOST = "net.pgmac.nagwatch.notify.HOST"
        private const val EXTRA_SERVICE = "net.pgmac.nagwatch.notify.SERVICE"
        private const val NO_PROFILE = -1L

        /** Null for an intent that is not one of ours, such as the launcher's. */
        fun from(intent: Intent?): NotificationTarget? {
            val profileId = intent?.getLongExtra(EXTRA_PROFILE, NO_PROFILE) ?: NO_PROFILE
            if (intent == null || profileId == NO_PROFILE) return null
            val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotEmpty() }
            val service = intent.getStringExtra(EXTRA_SERVICE)?.takeIf { it.isNotEmpty() }
            return NotificationTarget(profileId, host?.let { ObjectRef(it, service) })
        }
    }
}
