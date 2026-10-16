// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The signed release must look as it always has: no frame, no amber status bar, plain top bars.
 * Runs against the release build only (`testReleaseUnitTest`); CI runs it on every change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DebugChromeTest {
    /**
     * The compose test activity is declared by a library that only debug builds include, and the
     * release build must not gain it, so the test registers the activity itself, before the
     * compose rule starts it.
     */
    @get:Rule(order = 0)
    val registerComposeTestActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager)
                .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun `a release build draws no frame and no status bar over the app`() {
        var taps = 0
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                DebugChrome.Frame { Text("press me", Modifier.fillMaxSize().clickable { taps++ }) }
            }
        }

        composeRule.onNodeWithTag(DebugChrome.TAG_BORDER).assertDoesNotExist()
        composeRule.onNodeWithTag(DebugChrome.TAG_STATUS_BAR).assertDoesNotExist()
        composeRule.onNodeWithText("press me").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a release build's top bar has the colours Material gives it`() {
        lateinit var ours: TopAppBarColors
        lateinit var material: TopAppBarColors
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                ours = DebugChrome.topAppBarColors()
                material = TopAppBarDefaults.topAppBarColors()
            }
        }

        assertEquals(material, ours)
    }
}
