// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning the screen for the graph viewer and handing it back, step by step, without an
 * activity. What an activity does with each request was looked at on a phone; that these are the
 * requests made, in this order, is checked here.
 */
class TurnsTest {
    private class Screen(override var requestedOrientation: Int = UNSPECIFIED, override var upright: Boolean = true) :
        TurnableScreen {
        val asked = mutableListOf<Int>()

        fun record() = apply { asked += requestedOrientation }
    }

    @Test
    fun `the viewer turns the screen on its side and remembers how it was`() {
        val screen = Screen(requestedOrientation = USER, upright = true)

        val turn = Turns.sideways(Turn(), screen)

        assertEquals(LANDSCAPE, screen.requestedOrientation)
        assertEquals(Turn(Turn.Phase.SIDEWAYS, before = USER, wasUpright = true), turn)
    }

    @Test
    fun `rebuilt because the screen turned, the viewer does not mistake its own request for how things were`() {
        val screen = Screen()
        val first = Turns.sideways(Turn(), screen)
        // The activity has been rebuilt on its side, still asking for what the viewer asked.
        screen.upright = false

        val again = Turns.sideways(first, screen)

        assertEquals("what was there before anything was asked", first, again)
        assertEquals(UNSPECIFIED, again.before)
        assertEquals(true, again.wasUpright)
    }

    @Test
    fun `when the viewer goes, a screen that was upright is stood up again before it is let go`() {
        val screen = Screen()
        val sideways = Turns.sideways(Turn(), screen)

        val returning = Turns.back(sideways, screen)

        // Letting go alone leaves a phone lying flat on its side: there is no tilt to turn it by.
        assertEquals(PORTRAIT, screen.requestedOrientation)
        assertEquals(Turn.Phase.RETURNING, returning.phase)

        val done = Turns.released(returning, screen)

        assertEquals("left to itself, as before", UNSPECIFIED, screen.requestedOrientation)
        assertEquals(Turn(), done)
    }

    @Test
    fun `what the activity asked for before is what it asks for after`() {
        val screen = Screen(requestedOrientation = USER)

        val done = Turns.released(Turns.back(Turns.sideways(Turn(), screen), screen), screen)

        assertEquals(USER, screen.requestedOrientation)
        assertEquals(Turn(), done)
    }

    @Test
    fun `a screen that was already on its side is let go at once, and never stood up`() {
        val screen = Screen(upright = false)
        val sideways = Turns.sideways(Turn(), screen)

        val done = Turns.back(sideways, screen)

        assertEquals(UNSPECIFIED, screen.requestedOrientation)
        assertEquals(Turn(), done)
    }

    @Test
    fun `the viewer opened again while the screen is still being stood up keeps what was first remembered`() {
        val screen = Screen(requestedOrientation = USER)
        val returning = Turns.back(Turns.sideways(Turn(), screen), screen)
        // By now the activity is asking for upright, which is this app's doing, not how things were.
        screen.upright = true

        val again = Turns.sideways(returning, screen)

        assertEquals(LANDSCAPE, screen.requestedOrientation)
        assertEquals(Turn(Turn.Phase.SIDEWAYS, before = USER, wasUpright = true), again)
    }

    @Test
    fun `nothing is handed back that was not taken`() {
        val screen = Screen(requestedOrientation = USER)

        assertEquals(Turn(), Turns.back(Turn(), screen))
        assertEquals(Turn(), Turns.released(Turn(), screen))
        val sideways = Turns.sideways(Turn(), screen)
        assertEquals("not let go while the viewer is up", sideways, Turns.released(sideways, screen))
        assertEquals(LANDSCAPE, screen.requestedOrientation)
    }

    @Test
    fun `going back twice stands the screen up once`() {
        val screen = Screen()
        val returning = Turns.back(Turns.sideways(Turn(), screen), screen)
        screen.requestedOrientation = USER

        assertEquals(returning, Turns.back(returning, screen))
        assertEquals("untouched the second time", USER, screen.requestedOrientation)
    }

    private companion object {
        const val UNSPECIFIED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        const val USER = ActivityInfo.SCREEN_ORIENTATION_USER
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
