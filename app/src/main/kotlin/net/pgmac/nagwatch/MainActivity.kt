// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import net.pgmac.nagwatch.ui.PlaceholderScreen
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var appInfo: AppInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NagwatchTheme {
                PlaceholderScreen(versionName = appInfo.versionName)
            }
        }
    }
}
