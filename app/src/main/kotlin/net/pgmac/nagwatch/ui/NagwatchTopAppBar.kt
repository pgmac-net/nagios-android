// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.pgmac.nagwatch.debug.DebugChrome

/**
 * The top bar of every screen. It is the Material one, except that a debug build draws it amber
 * ([DebugChrome]); in a release build this is the plain top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NagwatchTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    DebugChrome.TopBar {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = DebugChrome.topAppBarColors(),
        )
    }
}
