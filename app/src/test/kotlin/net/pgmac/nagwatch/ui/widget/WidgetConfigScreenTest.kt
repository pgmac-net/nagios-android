// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.widget

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Choosing which profile a widget shows, and being told what a widget costs before choosing. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class WidgetConfigScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `it says that a widget means background checks, and that it does not turn notifications on`() {
        show(listOf(profile(1, "Home")))

        composeRule.onNodeWithTag(WidgetConfigTags.EXPLANATION).assertIsDisplayed()
        composeRule.onNodeWithText("check it in the background", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("does not turn them on", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("host and service names", substring = true).assertIsDisplayed()
    }

    @Test
    fun `each profile is listed with how often it is checked, and choosing one names it`() {
        show(listOf(profile(1, "Home"), profile(2, "Office", PollInterval.ONE_HOUR)))

        composeRule.onNodeWithText("Checked about every 15 minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Checked about every 1 hour").assertIsDisplayed()
        composeRule.onNodeWithTag(WidgetConfigTags.profile(2)).performClick()

        assertEquals(listOf("choose 2"), calls)
    }

    @Test
    fun `with no profiles it says so and offers the app, not an empty list`() {
        show(emptyList())

        composeRule.onNodeWithTag(WidgetConfigTags.NO_PROFILES).assertIsDisplayed()
        composeRule.onNodeWithTag(WidgetConfigTags.ADD_PROFILE).performClick()

        assertEquals(listOf("add"), calls)
    }

    @Test
    fun `while profiles are still being read it does not claim there are none`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { WidgetConfigContent(WidgetConfigState(loading = true), {}, {}, {}) }
        }

        composeRule.onNodeWithTag(WidgetConfigTags.NO_PROFILES).assertDoesNotExist()
    }

    @Test
    fun `Cancel leaves without choosing`() {
        show(listOf(profile(1, "Home")))

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("cancel"), calls)
    }

    private fun show(profiles: List<Profile>) = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            WidgetConfigContent(
                state = WidgetConfigState(loading = false, profiles = profiles),
                onChoose = { calls += "choose $it" },
                onAddProfile = { calls += "add" },
                onCancel = { calls += "cancel" },
            )
        }
    }

    private fun profile(id: Long, name: String, interval: PollInterval = PollInterval.FIFTEEN_MINUTES) = Profile(
        id = id,
        name = name,
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        hasPassword = true,
        accessClientId = "",
        hasAccessClientSecret = false,
        customHeaderNames = emptyList(),
        allowCleartext = false,
        notifications = NotificationSettings(interval = interval),
    )
}
