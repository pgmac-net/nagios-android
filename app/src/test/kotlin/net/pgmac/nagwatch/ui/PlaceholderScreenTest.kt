// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stands in for "the app launches" in CI, where there is no emulator: the
 * screen composes under the real theme, in light and dark, with real resources.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class PlaceholderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders app name, version and state swatches`() {
        composeRule.setContent {
            NagwatchTheme(darkTheme = false, dynamicColor = false) {
                PlaceholderScreen(versionName = "9.8.7")
            }
        }

        composeRule.onNodeWithText("Nagwatch", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PlaceholderScreenTags.VERSION).assertTextContains("9.8.7", substring = true)
        composeRule.onNodeWithTag(PlaceholderScreenTags.STATE_SWATCHES).assertIsDisplayed()
    }

    @Test
    fun `renders in dark theme with dynamic colour requested`() {
        composeRule.setContent {
            NagwatchTheme(darkTheme = true, dynamicColor = true) {
                PlaceholderScreen(versionName = "9.8.7")
            }
        }

        composeRule.onNodeWithTag(PlaceholderScreenTags.VERSION).assertIsDisplayed()
    }
}
