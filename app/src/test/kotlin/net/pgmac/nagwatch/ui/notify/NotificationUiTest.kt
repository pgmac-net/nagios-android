// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.notify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.notify.NotifyChannel
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The "Notifications" section of the profile editor, and the offer, as the person meets them. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var settings by mutableStateOf(NotificationSettings())
    private var refused by mutableStateOf(false)
    private val calls = mutableListOf<String>()

    private val actions = object : NotificationFieldActions {
        override fun setNotifications(enabled: Boolean) {
            calls += "set $enabled"
        }

        override fun editNotifications(change: (NotificationSettings) -> NotificationSettings) {
            settings = change(settings)
        }
    }

    @Test
    fun `off, the section is one switch and nothing to choose`() {
        showFields()

        composeRule.onNodeWithTag(NotificationTags.SWITCH).assertIsDisplayed().assertIsOff()
        composeRule.onNodeWithTag(NotificationTags.UNHANDLED_ONLY).assertDoesNotExist()
        composeRule.onNodeWithTag(NotificationTags.UNREACHABLE).assertDoesNotExist()
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.FIFTEEN_MINUTES)).assertDoesNotExist()
    }

    @Test
    fun `off, there is no way to the sounds, and on there is`() {
        showFields()
        composeRule.onNodeWithTag(NotificationTags.SOUNDS).assertDoesNotExist()
    }

    @Test
    fun `on, the sounds button makes the channels and sends the person to Android's page for them`() {
        settings = NotificationSettings(enabled = true)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.SOUNDS).performClick()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(context.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val made = context.getSystemService(NotificationManager::class.java).notificationChannels.map { it.id }
        assertEquals(NotifyChannel.entries.map { it.id }.toSet(), made.toSet())
    }

    @Test
    fun `the switch asks, it does not decide - Android may have to be asked first`() {
        showFields()

        composeRule.onNodeWithTag(NotificationTags.SWITCH).performClick()

        assertEquals(listOf("set true"), calls)
        composeRule.onNodeWithTag(NotificationTags.SWITCH).assertIsOff()
    }

    @Test
    fun `on, it shows both choices as they are set, and the interval is not among them`() {
        settings = NotificationSettings(enabled = true, interval = PollInterval.THIRTY_MINUTES, tellUnreachable = false)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.SWITCH).assertIsOn()
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.THIRTY_MINUTES)).assertDoesNotExist()
        composeRule.onNodeWithTag(NotificationTags.UNHANDLED_ONLY).assertIsOn()
        composeRule.onNodeWithTag(NotificationTags.UNREACHABLE).assertIsOff()
    }

    @Test
    fun `the two choices can be changed`() {
        settings = NotificationSettings(enabled = true)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.UNHANDLED_ONLY).performClick()
        composeRule.onNodeWithTag(NotificationTags.UNREACHABLE).performClick()

        assertEquals(NotificationSettings(enabled = true, unhandledOnly = false, tellUnreachable = false), settings)
    }

    @Test
    fun `background checks show the interval that is set, whether or not notifications are on`() {
        settings = NotificationSettings(enabled = false, interval = PollInterval.THIRTY_MINUTES)
        showBackgroundChecks()

        composeRule.onNodeWithText("Background checks").assertIsDisplayed()
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.THIRTY_MINUTES)).assertIsSelected()
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.FIFTEEN_MINUTES)).assertIsNotSelected()
        composeRule.onNodeWithText("a widget", substring = true).assertIsDisplayed()
    }

    @Test
    fun `choosing another interval changes it, and nothing else`() {
        settings = NotificationSettings(enabled = false, unhandledOnly = false)
        showBackgroundChecks()

        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.ONE_HOUR)).performClick()

        assertEquals(
            NotificationSettings(enabled = false, interval = PollInterval.ONE_HOUR, unhandledOnly = false),
            settings,
        )
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.ONE_HOUR)).assertIsSelected()
    }

    @Test
    fun `after a refusal it says why it is off and offers the way to allow them`() {
        refused = true
        showFields()

        composeRule.onNodeWithTag(NotificationTags.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithTag(NotificationTags.ALLOW).performClick()

        assertEquals("the button is the switch again", listOf("set true"), calls)
    }

    @Test
    fun `on but blocked, it says the checks go on and the news does not`() {
        settings = NotificationSettings(enabled = true)
        refused = true
        showFields()

        composeRule.onNodeWithTag(NotificationTags.REFUSED).assertTextContains("still checked", substring = true)
        composeRule.onNodeWithTag(NotificationTags.ALLOW).assertIsDisplayed()
    }

    @Test
    fun `off and refused, it says that is why it is off`() {
        refused = true
        showFields()

        composeRule.onNodeWithTag(NotificationTags.REFUSED).assertTextContains("stays off", substring = true)
    }

    @Test
    fun `without a refusal there is nothing about Android`() {
        showFields()

        composeRule.onNodeWithTag(NotificationTags.REFUSED).assertDoesNotExist()
        composeRule.onNodeWithTag(NotificationTags.ALLOW).assertDoesNotExist()
    }

    @Test
    fun `the offer names the profile and has two answers`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                NotificationOfferDialog("Home", onTurnOn = { calls += "on" }, onNotNow = { calls += "not now" })
            }
        }

        composeRule.onNodeWithTag(NotificationTags.OFFER).assertIsDisplayed()
        composeRule.onNodeWithText("Home", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(NotificationTags.OFFER_TURN_ON).performClick()
        composeRule.onNodeWithTag(NotificationTags.OFFER_NOT_NOW).performClick()

        assertEquals(listOf("on", "not now"), calls)
    }

    // ---------------------------------------------------------------- loud alerts

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun alarmsAllowed(allowed: Boolean) = ShadowAlarmManager.setCanScheduleExactAlarms(allowed)

    @Test
    fun `off, there is no loud alert switch, and on there is, off by default`() {
        showFields()
        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS).assertDoesNotExist()
    }

    @Test
    fun `on, loud alerts start off and can be switched on`() {
        alarmsAllowed(true)
        settings = NotificationSettings(enabled = true)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS).performScrollTo().assertIsOff().performClick()

        assertEquals(true, settings.loudAlerts)
        assertNull("nothing to ask for", shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun `switching loud alerts on without the alarm permission goes to Android's page for it`() {
        alarmsAllowed(false)
        settings = NotificationSettings(enabled = true)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS).performScrollTo().performClick()

        assertEquals(true, settings.loudAlerts)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
    }

    @Test
    fun `on without the permission, it says loud alerts cannot sound and offers the page again`() {
        alarmsAllowed(false)
        settings = NotificationSettings(enabled = true, loudAlerts = true)
        showFields()

        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS_BLOCKED).performScrollTo().assertExists()
        composeRule.onNodeWithTag(NotificationTags.ALLOW_ALARMS).performScrollTo().performClick()

        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            shadowOf(context as Application).nextStartedActivity.action,
        )
    }

    @Test
    fun `with the permission held, or loud alerts off, nothing is said about it`() {
        alarmsAllowed(true)
        settings = NotificationSettings(enabled = true, loudAlerts = true)
        showFields()
        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS_BLOCKED).assertDoesNotExist()

        alarmsAllowed(false)
        settings = NotificationSettings(enabled = true, loudAlerts = false)
        composeRule.onNodeWithTag(NotificationTags.LOUD_ALERTS_BLOCKED).assertDoesNotExist()
    }

    private fun showBackgroundChecks() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                Column { BackgroundCheckFields(settings.interval) { settings = settings.copy(interval = it) } }
            }
        }
    }

    private fun showFields() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NotificationFields(settings, refused, actions)
                }
            }
        }
    }
}
