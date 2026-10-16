// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The sound's life: the volume raised and put back, the time limit, one at a time. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class AlarmSoundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val ledger = MemoryLedger()
    private val tone = FakeTone()
    private val buzz = FakeBuzz()
    private var percent = 100
    private val sound = AlarmSound(audio, ledger, tone, buzz, { percent }, Handler(Looper.getMainLooper()))
    private var finished = 0

    private val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    private var alarmVolume: Int
        get() = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        set(value) = audio.setStreamVolume(AudioManager.STREAM_ALARM, value, 0)

    @Test
    fun `it raises the alarm volume, plays and buzzes, and puts the volume back when it ends`() {
        alarmVolume = 1

        assertTrue(sound.start { finished++ })

        assertEquals(max, alarmVolume)
        assertTrue(tone.playing && buzz.buzzing && sound.isSounding)
        sound.stop()
        assertEquals(1, alarmVolume)
        assertFalse(tone.playing || buzz.buzzing || sound.isSounding)
        assertEquals(1, finished)
    }

    @Test
    fun `it ends by itself after ten seconds`() {
        sound.start { finished++ }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AlarmSound.LONGEST_MS - 1))
        assertTrue(sound.isSounding)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertFalse(sound.isSounding)
        assertEquals(1, finished)
    }

    @Test
    fun `a second while one sounds starts nothing and changes nothing`() {
        sound.start { finished++ }

        assertFalse(sound.start { finished += 100 })

        assertEquals(1, tone.plays)
        sound.stop()
        assertEquals("only the first is told it ended", 1, finished)
    }

    @Test
    fun `a volume the person moved while it sounded is left where they put it`() {
        alarmVolume = 1
        sound.start {}

        alarmVolume = 3
        sound.stop()

        assertEquals(3, alarmVolume)
    }

    @Test
    fun `what the volume was is written down before it is changed, and forgotten after`() {
        alarmVolume = 2
        sound.start {}

        assertEquals(Raised(before = 2, set = max), ledger.raised)
        sound.stop()
        assertNull(ledger.raised)
    }

    @Test
    fun `a volume already where it should be is not touched or written down`() {
        alarmVolume = max
        sound.start {}

        assertNull(ledger.raised)
        sound.stop()
        assertEquals(max, alarmVolume)
    }

    @Test
    fun `a volume left raised by an app that was killed is put back at the next start`() {
        alarmVolume = max
        ledger.raised = Raised(before = 2, set = max)

        sound.putBackLeftover()

        assertEquals(2, alarmVolume)
        assertNull(ledger.raised)
    }

    @Test
    fun `with nothing left over the next start changes nothing`() {
        alarmVolume = 4

        sound.putBackLeftover()

        assertEquals(4, alarmVolume)
    }

    @Test
    fun `a sound that cannot be played still buzzes, and still ends`() {
        tone.works = false

        sound.start { finished++ }

        assertTrue(buzz.buzzing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AlarmSound.LONGEST_MS))
        assertEquals(1, finished)
    }

    @Test
    fun `stopping with nothing sounding does nothing`() {
        sound.stop()

        assertEquals(0, finished)
    }

    private class MemoryLedger : VolumeLedger {
        var raised: Raised? = null

        override fun save(raised: Raised) {
            this.raised = raised
        }

        override fun take(): Raised? = raised.also { raised = null }
    }

    private class FakeTone : Tone {
        var works = true
        var playing = false
        var plays = 0

        override fun play(): Boolean {
            plays++
            playing = works
            return works
        }

        override fun stop() {
            playing = false
        }
    }

    private class FakeBuzz : Buzz {
        var buzzing = false

        override fun start() {
            buzzing = true
        }

        override fun stop() {
            buzzing = false
        }
    }
}
