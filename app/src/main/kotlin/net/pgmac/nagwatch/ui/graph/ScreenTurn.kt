// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay

/**
 * Where turning the screen for the graph viewer has got to.
 *
 * The viewer turns the screen on its side, and when it closes the screen has to go back the way
 * it was. Letting go of the request is not enough: a phone lying flat gives Android nothing to
 * turn it back by, and it stays on its side until someone picks it up (seen on a phone). So a
 * screen that was upright is asked to be upright again, and only then left to itself.
 */
data class Turn(
    val phase: Phase = Phase.NOT_TURNED,
    /** What the activity asked for before the viewer asked for anything. */
    val before: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    val wasUpright: Boolean = true,
) {
    enum class Phase {
        NOT_TURNED,

        /** The viewer is up and has the screen on its side. */
        SIDEWAYS,

        /** The viewer has gone and the screen is being stood up again; not yet left to itself. */
        RETURNING,
    }
}

/** The part of an activity this needs, so that the steps can be tried without one. */
interface TurnableScreen {
    var requestedOrientation: Int
    val upright: Boolean
}

/** The steps. Each takes where things are and says where they are now; nothing else is remembered. */
object Turns {
    /** The viewer has appeared, or has been rebuilt because the screen turned. */
    fun sideways(turn: Turn, screen: TurnableScreen): Turn {
        // Only the first time is "before" looked at. After the screen has turned, the activity
        // is rebuilt and asked again, and by then what it finds is this app's own request.
        val started = when (turn.phase) {
            Turn.Phase.NOT_TURNED -> turn.copy(before = screen.requestedOrientation, wasUpright = screen.upright)
            Turn.Phase.SIDEWAYS, Turn.Phase.RETURNING -> turn
        }
        screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        return started.copy(phase = Turn.Phase.SIDEWAYS)
    }

    /** The viewer has gone for good (not merely been rebuilt). */
    @SuppressLint("SourceLockedOrientationActivity") // Held for a moment and let go: see released().
    fun back(turn: Turn, screen: TurnableScreen): Turn = when {
        turn.phase != Turn.Phase.SIDEWAYS -> turn

        turn.wasUpright -> {
            screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            turn.copy(phase = Turn.Phase.RETURNING)
        }

        // It was on its side already, by the person's own doing. Nothing to stand up again.
        else -> released(turn.copy(phase = Turn.Phase.RETURNING), screen)
    }

    /** The screen has had time to stand up. It is left to itself, as it was before. */
    fun released(turn: Turn, screen: TurnableScreen): Turn {
        if (turn.phase != Turn.Phase.RETURNING) return turn
        screen.requestedOrientation = turn.before
        return Turn()
    }

    /** Long enough for the screen to finish turning; short enough that nobody tries to turn it back first. */
    const val RELEASE_AFTER_MS = 1_500L
}

/** What the viewer is given: two things to say, and nothing to know. */
class ScreenTurn(val sideways: () -> Unit, val back: () -> Unit)

private val TurnSaver = listSaver<Turn, Int>(
    save = { listOf(it.phase.ordinal, it.before, if (it.wasUpright) 1 else 0) },
    restore = { Turn(Turn.Phase.entries[it[0]], it[1], it[2] == 1) },
)

/**
 * Kept where it outlives the viewer: by the navigation host, not by the viewer, because the
 * last step happens after the viewer has gone. Saved, because every turn of the screen rebuilds
 * the activity and everything remembered with it.
 */
@Composable
fun rememberScreenTurn(): ScreenTurn {
    val activity = LocalActivity.current
    val upright by rememberUpdatedState(LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE)
    var turn by rememberSaveable(stateSaver = TurnSaver) { mutableStateOf(Turn()) }
    val screen = remember(activity) { activity?.let { ActivityScreen(it) { upright } } }
    LaunchedEffect(turn.phase, screen) {
        if (turn.phase == Turn.Phase.RETURNING && screen != null) {
            delay(Turns.RELEASE_AFTER_MS)
            turn = Turns.released(turn, screen)
        }
    }
    return remember(screen) {
        ScreenTurn(
            sideways = { screen?.let { turn = Turns.sideways(turn, it) } },
            // A viewer that goes because the activity is being rebuilt has not gone.
            back = {
                screen?.takeIf { activity?.isChangingConfigurations != true }?.let { turn = Turns.back(turn, it) }
            },
        )
    }
}

private class ActivityScreen(private val activity: Activity, private val isUpright: () -> Boolean) : TurnableScreen {
    override var requestedOrientation: Int
        get() = activity.requestedOrientation
        set(value) {
            activity.requestedOrientation = value
        }
    override val upright: Boolean get() = isUpright()
}
