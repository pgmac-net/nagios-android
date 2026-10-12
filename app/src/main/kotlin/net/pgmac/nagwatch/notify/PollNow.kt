// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One background poll of a profile, now, because the person asked: the widget's Refresh button.
 *
 * It queues the same worker the schedule runs, with nothing to wait for. It does not wait for a
 * network either: someone who taps Refresh with none should see that Nagios could not be
 * reached, not nothing. Tapping again while one is queued or running changes nothing.
 */
@Singleton
class PollNow @Inject constructor(@ApplicationContext private val context: Context) {
    fun request(profileId: Long) {
        val request = OneTimeWorkRequestBuilder<PollWorker>()
            .setInputData(workDataOf(PollWorker.PROFILE_ID to profileId))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(profileId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "poll-requested"

        fun workName(profileId: Long): String = "poll-requested-$profileId"
    }
}
