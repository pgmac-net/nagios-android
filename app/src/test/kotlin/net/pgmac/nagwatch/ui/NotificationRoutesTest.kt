// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A tapped notification goes through the app's real routes to the right screen. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationRoutesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var target by mutableStateOf<NotificationTarget?>(null)

    @Test
    fun `a service's notification opens that service, on that profile`() {
        show()

        target = NotificationTarget(7, ObjectRef("web01", "Disk /var/lib (50%)"))

        composeRule.onNodeWithText("7: web01 / Disk /var/lib (50%)").assertIsDisplayed()
    }

    @Test
    fun `a host's notification opens that host`() {
        show()

        target = NotificationTarget(7, ObjectRef("web01"))

        composeRule.onNodeWithText("7: web01").assertIsDisplayed()
    }

    @Test
    fun `back from where a notification led returns to what was open before`() {
        show()
        target = NotificationTarget(7, ObjectRef("web01"))
        composeRule.onNodeWithText("7: web01").assertIsDisplayed()

        composeRule.onNodeWithText(BACK).performClick()

        composeRule.onNodeWithText(HOME).assertIsDisplayed()
    }

    @Test
    fun `a summary's notification returns to the tabs from wherever the app was`() {
        show()
        target = NotificationTarget(7, ObjectRef("web01", "Load"))
        composeRule.onNodeWithText("7: web01 / Load").assertIsDisplayed()

        target = NotificationTarget(7)

        composeRule.onNodeWithText(HOME).assertIsDisplayed()
    }

    @Test
    fun `tapping the notification for what is already open does not open it twice`() {
        show()
        target = NotificationTarget(7, ObjectRef("web01"))
        composeRule.onNodeWithText("7: web01").assertIsDisplayed()

        // The same place again, as a second tap would ask for.
        target = null
        composeRule.waitForIdle()
        target = NotificationTarget(7, ObjectRef("web01"))
        composeRule.onNodeWithText("7: web01").assertIsDisplayed()
        composeRule.onNodeWithText(BACK).performClick()

        composeRule.onNodeWithText(HOME).assertIsDisplayed()
    }

    private fun show() = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            val navController = rememberNavController()
            LaunchedEffect(target) { target?.let { navController.open(it) } }
            NavHost(navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) { Text(HOME) }
                detailDestinations { profileId, ref ->
                    Column {
                        Text("$profileId: " + listOfNotNull(ref.hostName, ref.description).joinToString(" / "))
                        Button(onClick = { navController.popBackStack() }) { Text(BACK) }
                    }
                }
            }
        }
    }

    private companion object {
        const val HOME = "the tabs"
        const val BACK = "back"
    }
}
