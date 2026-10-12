// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import net.pgmac.nagwatch.notify.PollWorker

/**
 * Runs one background poll of one profile now. Debug builds only; a release build has no such class.
 *
 * Android runs the real job about every fifteen minutes, and WorkManager refuses to run it
 * early even when Android is told to, so checking notifications on a phone would otherwise
 * take hours. This queues the very same worker once, with nothing to wait for:
 *
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.PollNowReceiver --el profile ID
 *
 * Only adb's shell and the system can send it (the manifest asks for the DUMP permission).
 * It does what the scheduled job does and no more: a profile that is gone or has notifications
 * off is not polled.
 */
class PollNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profileId = intent.getLongExtra(EXTRA_PROFILE, NO_PROFILE)
        if (profileId == NO_PROFILE) return
        val request = OneTimeWorkRequestBuilder<PollWorker>()
            .setInputData(workDataOf(PollWorker.PROFILE_ID to profileId))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val TAG = "poll-now"
        private const val NO_PROFILE = -1L
    }
}
