// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.ui.detail.DetailTags
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Host and service names are whatever someone typed into a Nagios config.
 * Each one here goes through the real navigation graph and has to arrive
 * exactly as it left: a name that is cut short or decoded twice would open
 * the wrong object, or none.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DetailRoutesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var destination by mutableStateOf("")

    @Test
    fun `a host name arrives as it was sent`() {
        show()

        AWKWARD.forEach { name ->
            open(Routes.host(PROFILE, name))
            composeRule.onNodeWithTag(DetailTags.TITLE).assertTextEquals(name)
            composeRule.onNodeWithTag(DetailTags.BACK).performClick()
        }
    }

    @Test
    fun `a service arrives with its host and its name, each as sent`() {
        show()

        AWKWARD.forEach { name ->
            open(Routes.service(PROFILE, "host-for $name", name))
            composeRule.onNodeWithTag(DetailTags.TITLE).assertTextEquals("host-for $name / $name")
            composeRule.onNodeWithTag(DetailTags.BACK).performClick()
        }
    }

    @Test
    fun `back from a detail screen returns to where it was opened`() {
        show()
        open(Routes.host(PROFILE, "web01"))

        composeRule.onNodeWithTag(DetailTags.BACK).performClick()

        composeRule.onNodeWithText(GO).assertIsDisplayed()
    }

    private fun open(target: String) {
        destination = target
        composeRule.onNodeWithText(GO).performClick()
    }

    private fun show() = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            val navController = rememberNavController()
            NavHost(navController, startDestination = START) {
                composable(START) { Button(onClick = { navController.navigate(destination) }) { Text(GO) } }
                detailDestinations(onBack = { navController.popBackStack() })
            }
        }
    }

    private companion object {
        const val PROFILE = 7L
        const val START = "start"
        const val GO = "go"

        val AWKWARD = listOf(
            "web01",
            "Disk /",
            "Disk /var/lib (50%)",
            "100% used",
            "%2F is not a slash",
            "a&b=c",
            "what?now#then",
            "plus+sign",
            "{braces} and \$dollar",
            "two  spaces",
            "naïve ✓ 日本",
            "back\\slash",
        )
    }
}
