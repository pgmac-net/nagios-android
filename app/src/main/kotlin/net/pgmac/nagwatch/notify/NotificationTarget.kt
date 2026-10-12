// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.pgmac.nagwatch.nagios.model.ObjectRef

/**
 * What a tap on a notification opens: a host, a service, or (with no [ref]) the profile's
 * Problems list.
 *
 * It travels in the intent that starts the app, and an intent is something any app can send.
 * `MainActivity` is exported, as a launcher activity has to be, so without care another app
 * could send it a made-up target and have Nagwatch switch profile and look up a host of its
 * choosing. So the intent is addressed to [DOOR], a second name for the activity that is not
 * exported, and [from] believes an intent only if it came by that name. Android will not
 * deliver another app's intent to a component that is not exported, and an intent sent to
 * `MainActivity` by its own name does not carry this one.
 */
data class NotificationTarget(val profileId: Long, val ref: ObjectRef? = null) {
    fun toIntent(context: Context): Intent = Intent()
        .setComponent(ComponentName(context.packageName, DOOR))
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
        /** The activity alias in the manifest, `android:exported="false"`. The two must agree; a test checks. */
        const val DOOR = "net.pgmac.nagwatch.notify.FromNotification"

        private const val SCHEME = "nagwatch"
        private const val EXTRA_PROFILE = "net.pgmac.nagwatch.notify.PROFILE"
        private const val EXTRA_HOST = "net.pgmac.nagwatch.notify.HOST"
        private const val EXTRA_SERVICE = "net.pgmac.nagwatch.notify.SERVICE"
        private const val NO_PROFILE = -1L

        /**
         * Null for an intent that did not come through [DOOR]: the launcher's, or one another
         * app sent to `MainActivity` with extras that look like ours. The whole component is
         * compared, this app's package and the door's name, not the name alone.
         *
         * Null as well for an intent Android is replaying: reopening the app from the recent
         * apps list hands back the intent that first started it, and a tap is acted on once,
         * not every time the app is reopened.
         */
        fun from(context: Context, intent: Intent?): NotificationTarget? {
            if (intent == null || !intent.cameByTheDoor(context) || intent.isReplayed()) return null
            val profileId = intent.getLongExtra(EXTRA_PROFILE, NO_PROFILE)
            if (profileId == NO_PROFILE) return null
            val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotEmpty() }
            val service = intent.getStringExtra(EXTRA_SERVICE)?.takeIf { it.isNotEmpty() }
            return NotificationTarget(profileId, host?.let { ObjectRef(it, service) })
        }

        private fun Intent.cameByTheDoor(context: Context): Boolean =
            component == ComponentName(context.packageName, DOOR)

        private fun Intent.isReplayed(): Boolean = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    }
}
