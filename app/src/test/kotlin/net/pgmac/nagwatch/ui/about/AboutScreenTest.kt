// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The About screens as the person meets them. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class AboutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    private fun showAbout(version: AppVersion = AppVersion("1.2.3", 7, debug = false), count: Int? = 155) =
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                AboutContent(
                    version = version,
                    libraryCount = count,
                    onOpenUrl = { calls += "url:$it" },
                    onOpenLicence = { calls += "licence:$it" },
                    onOpenLibraries = { calls += "libraries" },
                )
            }
        }

    @Test
    fun `it names the app and its version`() {
        showAbout()

        // The debug build names itself "Nagwatch (debug)"; whatever the build calls the app is what is shown.
        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertExists()
        composeRule.onNodeWithText("Version 1.2.3 (7)").assertIsDisplayed()
    }

    @Test
    fun `a debug build says so`() {
        showAbout(AppVersion("1.2.3", 7, debug = true))

        composeRule.onNodeWithText("Version 1.2.3 (7), debug build").assertIsDisplayed()
    }

    @Test
    fun `it says who owns it, how it is licensed, what it sends and who Nagios belongs to`() {
        showAbout()

        composeRule.onNodeWithText("© 2026 Paul Macdonnell and contributors").assertExists()
        composeRule.onNodeWithText("NO WARRANTY", substring = true).assertExists()
        composeRule.onNodeWithText("No analytics and no crash reporter", substring = true).assertExists()
        composeRule.onNodeWithText("registered trademark of Nagios Enterprises", substring = true).assertExists()
    }

    @Test
    fun `the links open the source, the issues and the security policy`() {
        showAbout()

        composeRule.onNodeWithTag(AboutTags.SOURCE).performScrollTo().performClick()
        composeRule.onNodeWithTag(AboutTags.ISSUES).performScrollTo().performClick()
        composeRule.onNodeWithTag(AboutTags.SECURITY).performScrollTo().performClick()

        assertEquals(
            listOf(
                "url:https://github.com/pgmac-net/nagios-android",
                "url:https://github.com/pgmac-net/nagios-android/issues",
                "url:https://github.com/pgmac-net/nagios-android/security/policy",
            ),
            calls,
        )
    }

    @Test
    fun `the licence rows open Nagwatch's own licence and the list of libraries, which says how many`() {
        showAbout()

        composeRule.onNodeWithText("Open-source licences (155)").assertExists()
        composeRule.onNodeWithTag(AboutTags.OWN_LICENCE).performScrollTo().performClick()
        composeRule.onNodeWithTag(AboutTags.LIBRARIES).performScrollTo().performClick()

        assertEquals(listOf("licence:GPL-3.0-or-later", "libraries"), calls)
    }

    @Test
    fun `the list of libraries shows each with its licence, and a tap opens the text`() {
        val libraries = listOf(
            Library("a:one", "1", "One", "Apache-2.0", "Apache License 2.0"),
            Library("b:two", "2", "Two", null, "Home-made"),
        )
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                LicencesContent(libraries = libraries, onOpenLicence = { calls += it })
            }
        }

        composeRule.onNodeWithText("a:one 1").assertIsDisplayed()
        composeRule.onNodeWithText("Home-made").assertIsDisplayed()
        composeRule.onNodeWithTag(LicencesTags.library("a:one")).performClick()
        composeRule.onNodeWithTag(LicencesTags.library("b:two")).performClick()

        assertEquals(listOf("Apache-2.0"), calls)
    }

    @Test
    fun `with no list of libraries it says so`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { LicencesContent(libraries = emptyList(), onOpenLicence = {}) }
        }

        composeRule.onNodeWithTag(LicencesTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `a licence text is shown, and a missing one is explained`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { LicenceTextContent(spdxId = "MIT", text = "Permission is granted") }
        }
        composeRule.onNodeWithTag(LicenceTextTags.TEXT).assertIsDisplayed()
    }

    @Test
    fun `an unknown licence has no text and says so`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { LicenceTextContent(spdxId = "Nope", text = "") }
        }
        composeRule.onNodeWithText("not available", substring = true).assertIsDisplayed()
    }
}
