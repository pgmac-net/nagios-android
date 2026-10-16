// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import net.pgmac.nagwatch.R

/**
 * What tells a debug build apart from a release one once the app is open: an amber frame round
 * the window, an amber status bar and amber top bars, in the colour of the debug icon.
 *
 * Debug builds only. `src/release` has a copy of this object that changes nothing, so the signed
 * app carries none of it. Keep the two the same shape.
 */
object DebugChrome {
    const val TAG_BORDER = "debug_chrome_border"
    const val TAG_STATUS_BAR = "debug_chrome_status_bar"

    private val BORDER_WIDTH = 3.dp

    /** The debug icon's colour, so there is one amber. */
    @Composable
    private fun amber() = colorResource(R.color.ic_launcher_background)

    /**
     * [content] with the amber status bar and frame drawn over it. Both take no touch and move
     * nothing: the frame is over the outer few dp, where screens are padded anyway.
     */
    @Composable
    fun Frame(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            content()
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .align(Alignment.TopStart).background(amber()).testTag(TAG_STATUS_BAR),
            )
            Box(Modifier.fillMaxSize().border(BORDER_WIDTH, amber()).testTag(TAG_BORDER))
        }
    }

    /** Amber with black text and icons, in light and dark theme alike, scrolled or not. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = amber(),
        scrolledContainerColor = amber(),
        navigationIconContentColor = Color.Black,
        titleContentColor = Color.Black,
        actionIconContentColor = Color.Black,
    )

    /**
     * Wraps a top bar. Buttons in a top bar take the theme's primary colour, which on amber can
     * hardly be read (a green or Material You one, in dark theme a pale one), so here it is black.
     */
    @Composable
    fun TopBar(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Black), content = content)
    }

    /** Dark status-bar icons, because white ones on amber can hardly be read. After `enableEdgeToEdge`. */
    fun applyToWindow(activity: ComponentActivity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = true
    }
}
