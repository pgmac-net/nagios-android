// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a debug build adds to tell itself apart. The release copy is tested in `src/testRelease`. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DebugChromeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the frame and the amber status bar are drawn, and what is under them still takes a tap`() {
        var taps = 0
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                DebugChrome.Frame { Text("press me", Modifier.fillMaxSize().clickable { taps++ }) }
            }
        }

        composeRule.onNodeWithTag(DebugChrome.TAG_BORDER).assertIsDisplayed()
        composeRule.onNodeWithTag(DebugChrome.TAG_STATUS_BAR).assertExists()
        composeRule.onNodeWithText("press me").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `the top bar is amber with black text and icons, scrolled or not`() {
        val amber = Color(0xFFFF8F00)
        lateinit var colors: androidx.compose.material3.TopAppBarColors
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { colors = DebugChrome.topAppBarColors() }
        }

        assertEquals(amber, colors.containerColor)
        assertEquals(amber, colors.scrolledContainerColor)
        assertEquals(Color.Black, colors.titleContentColor)
        assertEquals(Color.Black, colors.navigationIconContentColor)
        assertEquals(Color.Black, colors.actionIconContentColor)
    }
}
