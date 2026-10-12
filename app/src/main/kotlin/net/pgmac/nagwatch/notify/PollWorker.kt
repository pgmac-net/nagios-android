// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * WorkManager's side of a background poll. Everything it does is in [BackgroundPoller]; this
 * only finds it, because WorkManager makes this object itself and cannot be handed one.
 */
class PollWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getLong(PROFILE_ID, NO_PROFILE)
        if (profileId != NO_PROFILE) {
            EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).backgroundPoller()
                .poll(profileId)
        }
        // A poll that failed is a result, and is counted as one. It is not a reason to try again
        // sooner: WorkManager's retry would poll more often than the person asked for.
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun backgroundPoller(): BackgroundPoller
    }

    companion object {
        const val PROFILE_ID = "profile_id"
        private const val NO_PROFILE = -1L
    }
}
