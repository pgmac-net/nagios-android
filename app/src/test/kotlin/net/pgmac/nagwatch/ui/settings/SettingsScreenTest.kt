// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.notify.EmojiSet
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The emoji choice as the person meets it. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chosen = mutableListOf<EmojiSet>()

    private val largeIcons = mutableListOf<Boolean>()

    private val asked = mutableListOf<String>()

    private fun show(set: EmojiSet, largeIcon: Boolean = true, loudAlert: LoudAlertSection = LoudAlertSection()) =
        composeRule.setContent {
            NagwatchTheme {
                SettingsContent(
                    chosen = set,
                    onChoose = { chosen += it },
                    largeIcon = largeIcon,
                    onLargeIcon = { largeIcons += it },
                    resources = context.resources,
                    loudAlert = loudAlert.copy(
                        onTest = { asked += "test" },
                        onStop = { asked += "stop" },
                        onAllow = { asked += "allow" },
                    ),
                )
            }
        }

    @Test
    fun `every set is offered, each with a look at it, and the current one is marked`() {
        show(EmojiSet.SIGNS)

        EmojiSet.entries.forEach { composeRule.onNodeWithTag(SettingsTags.emojiSet(it)).assertExists() }
        composeRule.onNodeWithText("🚨 ⚠️ ❓ ✅").assertExists()
        composeRule.onNodeWithText("Coloured circles").assertExists()
        composeRule.onNodeWithTag(SettingsTags.emojiSet(EmojiSet.SIGNS)).assertIsSelected()
    }

    @Test
    fun `pressing a set chooses it`() {
        show(EmojiSet.CIRCLES)

        composeRule.onNodeWithTag(SettingsTags.emojiSet(EmojiSet.FACES)).performScrollTo().performClick()

        assertEquals(listOf(EmojiSet.FACES), chosen)
    }

    @Test
    fun `the coloured picture has a switch that is on, and turning it off says so`() {
        show(EmojiSet.CIRCLES, largeIcon = true)

        composeRule.onNodeWithTag(SettingsTags.LARGE_ICON).performScrollTo().assertIsOn().performClick()

        assertEquals(listOf(false), largeIcons)
    }

    @Test
    fun `the loud alert volume shows what is set, and Test plays it`() {
        show(EmojiSet.CIRCLES, loudAlert = LoudAlertSection(volume = 65))

        composeRule.onNodeWithText("65%").performScrollTo().assertExists()
        composeRule.onNodeWithTag(
            SettingsTags.LOUD_ALERT_TEST,
        ).performScrollTo().assertTextEquals("Test").performClick()

        assertEquals(listOf("test"), asked)
    }

    @Test
    fun `while one sounds the button stops it`() {
        show(EmojiSet.CIRCLES, loudAlert = LoudAlertSection(sounding = true))

        composeRule.onNodeWithTag(
            SettingsTags.LOUD_ALERT_TEST,
        ).performScrollTo().assertTextEquals("Stop").performClick()

        assertEquals(listOf("stop"), asked)
    }

    @Test
    fun `without the alarm permission it says loud alerts cannot sound, and offers the way to allow it`() {
        show(EmojiSet.CIRCLES, loudAlert = LoudAlertSection(allowed = false))

        composeRule.onNodeWithTag(SettingsTags.LOUD_ALERT_BLOCKED).performScrollTo().assertExists()
        composeRule.onNodeWithText("Allow alarms").performScrollTo().performClick()

        assertEquals(listOf("allow"), asked)
    }

    @Test
    fun `with the permission held nothing is said about it`() {
        show(EmojiSet.CIRCLES)

        composeRule.onNodeWithTag(SettingsTags.LOUD_ALERT_BLOCKED).assertDoesNotExist()
    }
}
