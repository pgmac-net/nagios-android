// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import net.pgmac.nagwatch.notify.LoudAlert

/**
 * Sounds a loud alert from a background worker, after a delay. Debug builds only.
 *
 * A loud alert's hard part is being heard from the background on Android 17 (docs/adr/0013),
 * and the Settings test is played from the foreground, where none of that applies. This plays it
 * the way a poll would, from a worker, after [EXTRA_DELAY] seconds (15 unless given) so that the
 * phone can be locked, put on silent or into Do Not Disturb first:
 *
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.LoudAlertNowReceiver --ei delay 20
 *
 * Same DUMP permission as [PollNowReceiver], for the same reason.
 */
class LoudAlertNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val delay = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY_S).toLong()
        val request = OneTimeWorkRequestBuilder<LoudAlertWorker>().setInitialDelay(delay, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueue(request)
    }

    class LoudAlertWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result {
            val loudAlert =
                EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).loudAlert()
            // As the notifier does: only when it can be heard.
            if (loudAlert.canSound()) loudAlert.sound()
            return Result.success()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun loudAlert(): LoudAlert
    }

    companion object {
        const val EXTRA_DELAY = "delay"
        private const val DEFAULT_DELAY_S = 15
    }
}
