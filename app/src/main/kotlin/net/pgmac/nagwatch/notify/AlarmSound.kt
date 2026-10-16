// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The sound and the buzz of a loud alert, for at most [LONGEST_MS], at the volume the person set.
 *
 * The alarm stream is raised to that volume first and put back afterwards. What it was is written
 * down before it is changed, so that an app killed mid-sound puts it back on its next start
 * ([putBackLeftover]). Main thread only: the player and the timer are on it.
 */
@Singleton
class AlarmSound internal constructor(
    private val audio: AudioManager,
    private val ledger: VolumeLedger,
    private val tone: Tone,
    private val buzz: Buzz,
    /** The person's setting, read each time: a change shows on the next alert. */
    private val percent: () -> Int,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: NotifierSettings,
    ) : this(
        audio = context.getSystemService(AudioManager::class.java),
        ledger = SharedPreferencesVolumeLedger(context),
        tone = AlarmTone(context),
        buzz = AlarmBuzz(context),
        // Small, and on the main thread only for the length of one read of one file.
        percent = { runBlocking { settings.loudAlertVolume.first() } },
    )

    private val _sounding = MutableStateFlow(false)

    /** Whether one is sounding now, for a screen that offers to stop it. */
    val sounding: StateFlow<Boolean> = _sounding.asStateFlow()

    val isSounding: Boolean get() = _sounding.value

    private var onDone: (() -> Unit)? = null
    private var raised: Raised? = null
    private val timeUp = Runnable { stop() }

    /**
     * Starts one, unless one is sounding already. [done] is called when it ends, however it ends.
     *
     * @return false when one was sounding already, and nothing was started.
     */
    fun start(done: () -> Unit): Boolean {
        if (isSounding) return false
        _sounding.value = true
        onDone = done
        raised = raise()
        val playing = tone.play()
        buzz.start()
        if (!playing) Log.w(TAG, "No alarm sound could be played; the buzz is all there is")
        handler.postDelayed(timeUp, LONGEST_MS)
        return true
    }

    /** Ends one that is sounding, and puts the volume back. Nothing happens if none is. */
    fun stop() {
        if (!isSounding) return
        handler.removeCallbacks(timeUp)
        tone.stop()
        buzz.stop()
        raised?.let(::putBack)
        raised = null
        _sounding.value = false
        onDone?.also { onDone = null }?.invoke()
    }

    /** For app start: a volume left raised by an app that was killed before it could put it back. */
    fun putBackLeftover() {
        if (isSounding) return
        ledger.take()?.let(::putBack)
    }

    private fun raise(): Raised? {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val set = AlarmVolume.step(percent(), max)
        if (set == before) return null
        // Written down first: once the volume is changed, a kill must not lose what it was.
        val raised = Raised(before = before, set = set)
        ledger.save(raised)
        return try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, set, 0)
            raised
        } catch (refused: SecurityException) {
            // Played at the person's own alarm volume instead: quieter, but still heard.
            Log.w(TAG, "The alarm volume could not be set", refused)
            ledger.take()
            null
        }
    }

    private fun putBack(raised: Raised) {
        ledger.take()
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val back = AlarmVolume.restore(current = current, set = raised.set, before = raised.before) ?: return
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, back, 0)
        } catch (refused: SecurityException) {
            Log.w(TAG, "The alarm volume could not be put back", refused)
        }
    }

    companion object {
        val LONGEST_MS: Long = TimeUnit.SECONDS.toMillis(10)
        private const val TAG = "AlarmSound"
    }
}

/** The alarm volume before a loud alert raised it, and what it was raised to. */
data class Raised(val before: Int, val set: Int)

/**
 * Where [Raised] is written down while a loud alert sounds.
 *
 * SharedPreferences, not the DataStore the rest of the settings use: it must be on disk before the
 * volume changes, from the main thread, and `commit` is that.
 */
interface VolumeLedger {
    fun save(raised: Raised)

    /** What was written down, if anything, and forgets it. */
    fun take(): Raised?
}

class SharedPreferencesVolumeLedger(context: Context) : VolumeLedger {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun save(raised: Raised) {
        prefs.edit(commit = true) {
            putInt(BEFORE, raised.before)
            putInt(SET, raised.set)
        }
    }

    override fun take(): Raised? {
        if (!prefs.contains(BEFORE) || !prefs.contains(SET)) return null
        val raised = Raised(before = prefs.getInt(BEFORE, 0), set = prefs.getInt(SET, 0))
        prefs.edit(commit = true) { clear() }
        return raised
    }

    private companion object {
        const val FILE = "loud_alert"
        const val BEFORE = "alarm_volume_before"
        const val SET = "alarm_volume_set"
    }
}

/** The sound of a loud alert. */
interface Tone {
    /** @return false when nothing could be played. */
    fun play(): Boolean

    fun stop()
}

/** Buzzing that goes with the sound, as an alarm so that Do Not Disturb lets it through too. */
interface Buzz {
    fun start()

    fun stop()
}

/**
 * The phone's own alarm sound, played as an alarm, round and round until stopped. Android's
 * bundled sounds differ from phone to phone; this is the one the person chose to be woken by.
 */
private class AlarmTone(private val context: Context) : Tone {
    private var player: MediaPlayer? = null

    override fun play(): Boolean {
        stop()
        // The alarm sound, and if there is none set (or it is gone) the notification sound: still heard.
        val played = listOf(Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
            .firstNotNullOfOrNull { uri ->
                try {
                    MediaPlayer().apply {
                        setAudioAttributes(ALARM)
                        setDataSource(context, uri)
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (failed: IOException) {
                    Log.w(TAG, "Could not play $uri", failed)
                    null
                } catch (failed: IllegalStateException) {
                    Log.w(TAG, "Could not play $uri", failed)
                    null
                }
            }
        player = played
        return played != null
    }

    override fun stop() {
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // Never started: there is nothing to stop, only to release.
            }
            release()
        }
        player = null
    }

    companion object {
        private const val TAG = "AlarmTone"
    }
}

private class AlarmBuzz(context: Context) : Buzz {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun start() {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM)
        }
    }

    override fun stop() {
        vibrator?.cancel()
    }

    private companion object {
        /** Off, on, off: repeated from the start until stopped. */
        val PATTERN = longArrayOf(0, 800, 400)
    }
}

private val ALARM: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()
