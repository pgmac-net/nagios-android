// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.ui.NagwatchNavHost
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var selectedProfile: SelectedProfile

    /** Where a tapped notification asked to go, until the screens have gone there. */
    private val target = MutableStateFlow<NotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the same intent is still there, already acted on.
        if (savedInstanceState == null) open(intent)
        setContent {
            val opening by target.collectAsStateWithLifecycle()
            NagwatchTheme {
                NagwatchNavHost(target = opening, onTargetOpened = { target.value = null })
            }
        }
    }

    /** A notification tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val asked = NotificationTarget.from(this, intent) ?: return
        // The notification was about one profile; show that one, whichever was on screen.
        lifecycleScope.launch { selectedProfile.select(asked.profileId) }
        target.value = asked
    }
}
