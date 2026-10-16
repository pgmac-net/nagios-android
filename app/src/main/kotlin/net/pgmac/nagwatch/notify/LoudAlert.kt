// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A down or critical problem heard through silent mode and Do Not Disturb: the phone's alarm
 * sound, played by the app as an alarm, at the app's own volume (docs/adr/0013).
 *
 * Whether one is called for is [AndroidNotifier]'s to decide; this is only the sound.
 */
interface LoudAlert {
    /**
     * Whether Android will let one be heard from the background. From Android 17 that takes the
     * exact-alarm permission ("Alarms & reminders"), which the person can refuse or take back.
     * Without it a critical notification makes its channel's own sound instead.
     */
    fun canSound(): Boolean

    /** Sounds one, unless one is sounding already. Nothing is queued. */
    fun sound()

    /** Stops one that is sounding. Nothing happens if none is. */
    fun stop()

    /** Whether one is sounding now. */
    val sounding: StateFlow<Boolean>

    companion object {
        /** For a notifier that never makes one: tests, and nothing else. */
        val NONE: LoudAlert = object : LoudAlert {
            override val sounding: StateFlow<Boolean> = MutableStateFlow(false)

            override fun canSound() = false

            override fun sound() = Unit

            override fun stop() = Unit
        }
    }
}

/** The arithmetic of a loud alert's volume, apart from Android so that it can be tested. */
object AlarmVolume {
    const val LOWEST = 1
    const val HIGHEST = 100
    const val DEFAULT = 80

    /** A percentage as stored, or the default for none. Never 0: a silent loud alert is switched off instead. */
    fun percent(stored: Int?): Int = (stored ?: DEFAULT).coerceIn(LOWEST, HIGHEST)

    /**
     * The step to set an alarm stream with [max] steps to. Rounded up, and never below 1, so that
     * the lowest setting is still something to hear.
     */
    fun step(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        return ((percent(percent) * max + HIGHEST - 1) / HIGHEST).coerceIn(1, max)
    }

    /**
     * What to put the stream back to when the sound ends: [before], unless the volume is no longer
     * the one the app [set]. Then the person has moved it meanwhile, and their choice stands.
     */
    fun restore(current: Int, set: Int, before: Int): Int? = if (current == set) before else null
}

@Singleton
class AndroidLoudAlert @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmSound: AlarmSound,
) : LoudAlert {
    override fun canSound(): Boolean = canScheduleExactAlarms(context)

    override fun sound() {
        if (alarmSound.isSounding) return
        try {
            // The service makes itself foreground and plays. Android 17 hears nothing from an app
            // in the background without one; the exact-alarm permission is what lets it start.
            ContextCompat.startForegroundService(context, LoudAlertService.start(context))
        } catch (refused: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one. The notification is already
            // posted; it is the loud part that is lost, and there is no one to tell but the log.
            Log.w(TAG, "A loud alert could not start", refused)
        }
    }

    override fun stop() = alarmSound.stop()

    override val sounding: StateFlow<Boolean> get() = alarmSound.sounding

    private companion object {
        const val TAG = "LoudAlert"
    }
}

/** Below Android 12 there is no such permission to hold, and nothing is asked. */
fun canScheduleExactAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
