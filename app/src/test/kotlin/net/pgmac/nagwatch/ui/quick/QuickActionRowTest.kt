// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.quick

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A row's two gestures, and what its menu offers. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class QuickActionRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val disk = ObjectRef("web01", "Disk /")
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(QuickUiState(permission = CommandPermission.ALLOWED))
    private val actions = object : QuickActions {
        override fun opened() {
            calls += "opened"
        }

        override fun run(action: QuickAction, target: ObjectRef) {
            calls += "${action.name} ${target.hostName}/${target.description}"
        }

        override fun undo() {
            calls += "undo"
        }

        override fun dismiss(progress: ActionProgress) {
            calls += "dismiss"
        }
    }

    @Test
    fun `a tap opens the row and a long press opens its actions`() {
        show(problem())

        composeRule.onNodeWithTag(ROW).performClick()
        assertEquals(listOf("open"), calls)
        composeRule.onNodeWithTag(QuickTags.MENU).assertDoesNotExist()

        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.MENU).assertExists()
        assertEquals("the menu asks what this user may do", listOf("open", "opened"), calls)
    }

    @Test
    fun `a problem nobody has acknowledged can be acknowledged and rechecked, and there is nothing to remove`() {
        show(problem())
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).assertIsEnabled()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.RECHECK)).assertIsEnabled()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.REMOVE_ACKNOWLEDGEMENT)).assertDoesNotExist()
        composeRule.onNodeWithTag(QuickTags.OPEN).assertExists()
    }

    @Test
    fun `an acknowledged problem offers to remove it, and not to acknowledge again`() {
        show(problem(acknowledged = true))
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.item(QuickAction.REMOVE_ACKNOWLEDGEMENT)).assertIsEnabled()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).assertDoesNotExist()
    }

    @Test
    fun `something that is not a problem can only be rechecked or opened`() {
        show(QuickTarget(disk, isProblem = false, acknowledged = false))
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.item(QuickAction.RECHECK)).assertIsEnabled()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).assertDoesNotExist()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.REMOVE_ACKNOWLEDGEMENT)).assertDoesNotExist()
    }

    @Test
    fun `choosing an action sends it for this row and closes the menu`() {
        show(problem())
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).performClick()

        assertEquals(listOf("opened", "ACKNOWLEDGE web01/Disk /"), calls)
        composeRule.onNodeWithTag(QuickTags.MENU).assertDoesNotExist()
    }

    @Test
    fun `Open in the menu does what a tap does`() {
        show(problem())
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.OPEN).performClick()

        assertEquals(listOf("opened", "open"), calls)
    }

    @Test
    fun `a read-only user sees the actions switched off, with the reason, and can still open`() {
        state = QuickUiState(permission = CommandPermission.READ_ONLY)
        show(problem())
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.READ_ONLY).assertExists()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).assertIsNotEnabled()
        composeRule.onNodeWithTag(QuickTags.item(QuickAction.RECHECK)).assertIsNotEnabled()
        composeRule.onNodeWithTag(QuickTags.OPEN).assertIsEnabled()
    }

    @Test
    fun `while a command is on its way the actions are off`() {
        state =
            state.copy(progress = ActionProgress.Sending(net.pgmac.nagwatch.nagios.command.Command.ForceCheck(disk)))
        show(problem())
        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.item(QuickAction.ACKNOWLEDGE)).assertIsNotEnabled()
    }

    @Test
    fun `a list shown without quick actions has no menu to open`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                QuickActionRow(problem(), quick = null, onOpen = { calls += "open" }) { gestures ->
                    Text("web01 / Disk /", modifier = gestures.testTag(ROW))
                }
            }
        }

        composeRule.onNodeWithTag(ROW).performTouchInput { longClick() }

        composeRule.onNodeWithTag(QuickTags.MENU).assertDoesNotExist()
    }

    private fun problem(acknowledged: Boolean = false) =
        QuickTarget(disk, isProblem = true, acknowledged = acknowledged)

    private fun show(target: QuickTarget) = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            QuickActionRow(target, QuickMenu(state, actions), onOpen = { calls += "open" }) { gestures ->
                Text("web01 / Disk /", modifier = gestures.testTag(ROW))
            }
        }
    }

    private companion object {
        const val ROW = "row"
    }
}
