// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmVolumeTest {
    @Test
    fun `nothing stored is the default, and what is stored is kept within bounds`() {
        assertEquals(AlarmVolume.DEFAULT, AlarmVolume.percent(null))
        assertEquals(AlarmVolume.LOWEST, AlarmVolume.percent(0))
        assertEquals(AlarmVolume.HIGHEST, AlarmVolume.percent(250))
        assertEquals(42, AlarmVolume.percent(42))
    }

    @Test
    fun `a percentage becomes a step of the stream, rounded up`() {
        assertEquals(6, AlarmVolume.step(80, max = 7))
        assertEquals(7, AlarmVolume.step(100, max = 7))
        assertEquals(4, AlarmVolume.step(50, max = 7))
    }

    @Test
    fun `the lowest setting is never silence`() {
        assertEquals(1, AlarmVolume.step(1, max = 7))
        assertEquals(1, AlarmVolume.step(0, max = 7))
    }

    @Test
    fun `a stream with no steps has nothing to set`() {
        assertEquals(0, AlarmVolume.step(80, max = 0))
    }

    @Test
    fun `the volume goes back only if the person has not moved it meanwhile`() {
        assertEquals(2, AlarmVolume.restore(current = 6, set = 6, before = 2))
        assertNull(AlarmVolume.restore(current = 3, set = 6, before = 2))
    }
}
