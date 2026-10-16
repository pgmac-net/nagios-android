// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * The release build's copy of `src/debug`'s `DebugChrome`: it changes nothing. The signed app has
 * no frame, no coloured bars and nothing of how they are drawn. Keep the two the same shape.
 */
object DebugChrome {
    const val TAG_BORDER = "debug_chrome_border"
    const val TAG_STATUS_BAR = "debug_chrome_status_bar"

    @Composable
    fun Frame(content: @Composable () -> Unit) = content()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors()

    @Composable
    fun TopBar(content: @Composable () -> Unit) = content()

    @Suppress("UNUSED_PARAMETER")
    fun applyToWindow(activity: ComponentActivity) = Unit
}
