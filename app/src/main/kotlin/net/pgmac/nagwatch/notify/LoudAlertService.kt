// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import net.pgmac.nagwatch.R

/**
 * Where a loud alert plays: a foreground service, for as long as the sound lasts and no longer.
 *
 * Android 17 silences an app in the background that plays sound or changes a volume, unless it
 * has a foreground service running and, for one started from the background, holds the exact-alarm
 * permission and plays as an alarm (docs/adr/0013). The service's notification is the way to stop it.
 */
@AndroidEntryPoint
class LoudAlertService : Service() {
    @Inject
    lateinit var alarmSound: AlarmSound

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android ends the app if a service started this way does not go foreground in time,
        // so it does, whatever happens next.
        NotifyChannel.createAll(this)
        ServiceCompat.startForeground(this, ID_SOUNDING, sounding(), FOREGROUND_TYPE)
        val started = alarmSound.start(done = ::finish)
        // One already sounding goes on, and its end finishes the service.
        if (!started && !alarmSound.isSounding) finish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Stopped by Android: the sound goes with it, and the volume is put back.
        alarmSound.stop()
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sounding() = NotificationCompat.Builder(this, NotifyChannel.SOUNDING.id)
        .setSmallIcon(R.drawable.ic_tab_problems)
        .setContentTitle(getString(R.string.loud_alert_sounding_title))
        .setContentText(getString(R.string.loud_alert_sounding_text))
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setOngoing(true)
        .setSilent(true)
        .also { builder ->
            // Opening the app stops it too: the person has heard.
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        .addAction(0, getString(R.string.action_stop), StopLoudAlertReceiver.pendingIntent(this))
        .build()

    companion object {
        /** Apart from the notifier's own ids, which are tagged by profile; this one has no tag. */
        const val ID_SOUNDING = 109

        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }

        fun start(context: Context): Intent = Intent(context, LoudAlertService::class.java)
    }
}

/**
 * Where the exact alarm [AndroidLoudAlert] sets for now goes off. Android lets the app start a
 * foreground service while it handles one, which a poll in the background cannot. Not exported.
 */
@AndroidEntryPoint
class LoudAlertAlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var loudAlert: AndroidLoudAlert

    override fun onReceive(context: Context, intent: Intent) = loudAlert.startService()

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, LoudAlertAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Stops a loud alert: the Stop button on its own notification, and a loud notification swiped away.
 * Not exported. A broadcast, not the service, so that a stop with nothing sounding starts nothing.
 */
@AndroidEntryPoint
class StopLoudAlertReceiver : BroadcastReceiver() {
    @Inject
    lateinit var loudAlert: LoudAlert

    override fun onReceive(context: Context, intent: Intent) = loudAlert.stop()

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopLoudAlertReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
