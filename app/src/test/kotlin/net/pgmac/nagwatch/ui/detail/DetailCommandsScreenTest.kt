// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.ZoneOffset
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.AnnotationSection
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.comment
import net.pgmac.nagwatch.status.downtime
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What can be done from the detail screen, and what it says became of it: the buttons, the
 * forms, and the line that tells accepted apart from done.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, qualifiers = "w360dp-h3000dp")
class DetailCommandsScreenTest : DetailScreenFixture() {
    @Test
    fun `an unacknowledged problem can be acknowledged, rechecked and commented on`() {
        show(service(hard))

        listOf(DetailTags.ACKNOWLEDGE, DetailTags.RECHECK, DetailTags.COMMENT).forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()
        }
        composeRule.onNodeWithTag(DetailTags.REMOVE_ACKNOWLEDGEMENT).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.READ_ONLY).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertDoesNotExist()
    }

    @Test
    fun `an acknowledged problem offers to remove the acknowledgement instead`() {
        show(service(hard.copy(acknowledged = true)))

        composeRule.onNodeWithTag(DetailTags.REMOVE_ACKNOWLEDGEMENT).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).assertDoesNotExist()
    }

    @Test
    fun `something that is not a problem cannot be acknowledged, and can still be rechecked`() {
        show(host())

        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.RECHECK).assertIsEnabled()
        composeRule.onNodeWithTag(DetailTags.COMMENT).assertIsEnabled()
    }

    @Test
    fun `nothing is offered for an object nothing is known about`() {
        show(DetailUiState(ObjectDetail(DISK_REF)))

        composeRule.onNodeWithTag(DetailTags.ACTIONS).assertDoesNotExist()
    }

    @Test
    fun `a read-only user sees the buttons disabled, and why`() {
        show(service(hard).copy(permission = CommandPermission.READ_ONLY))

        listOf(DetailTags.ACKNOWLEDGE, DetailTags.RECHECK, DetailTags.COMMENT).forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed().assertIsNotEnabled()
        }
        composeRule.onNodeWithTag(DetailTags.READ_ONLY)
            .assertTextEquals("This Nagios user is read-only, so actions are unavailable.")
        composeRule.onNodeWithTag(DetailTags.RECHECK).performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `when it is not known whether the user may, the buttons work`() {
        show(service(hard).copy(permission = CommandPermission.UNKNOWN))

        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).assertIsEnabled()
        composeRule.onNodeWithTag(DetailTags.READ_ONLY).assertDoesNotExist()
    }

    @Test
    fun `recheck is sent at a tap, with nothing to confirm`() {
        show(service(hard))

        composeRule.onNodeWithTag(DetailTags.RECHECK).performClick()

        assertEquals(listOf("recheck"), calls)
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `acknowledging needs a comment, and starts with sticky off, notify on, persistent off`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).performClick()

        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(DetailTags.FORM_STICKY).assertIsOff()
        composeRule.onNodeWithTag(DetailTags.FORM_NOTIFY).assertIsOn()
        composeRule.onNodeWithTag(DetailTags.FORM_PERSISTENT).assertIsOff()

        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("on it")
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsEnabled().performClick()

        assertEquals(listOf("acknowledge:on it|sticky=false|notify=true|persistent=false"), calls)
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.RECHECK).assertIsDisplayed()
    }

    @Test
    fun `each choice in the acknowledge form is passed on as chosen`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("mine")

        composeRule.onNodeWithTag(DetailTags.FORM_STICKY).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_NOTIFY).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_PERSISTENT).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).performClick()

        assertEquals(listOf("acknowledge:mine|sticky=true|notify=false|persistent=true"), calls)
    }

    @Test
    fun `turning sticky on brings a warning about what it hides`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.ACKNOWLEDGE).performClick()

        composeRule.onNodeWithTag(DetailTags.FORM_STICKY_NOTE)
            .assertTextContains("notifies again", substring = true)
        composeRule.onNodeWithTag(DetailTags.FORM_STICKY).performClick()

        composeRule.onNodeWithTag(DetailTags.FORM_STICKY_NOTE)
            .assertTextContains("even if it gets worse", substring = true)
    }

    @Test
    fun `a comment of nothing Nagios would keep cannot be sent`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.COMMENT).performClick()

        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput(" <>; ")

        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsNotEnabled()
    }

    @Test
    fun `when Nagios would alter a comment, the form shows what it will keep`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.COMMENT).performClick()

        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("plain words")
        composeRule.onNodeWithTag(DetailTags.FORM_STORED_AS).assertDoesNotExist()

        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextReplacement("see <b>INC-42</b>")
        composeRule.onNodeWithTag(DetailTags.FORM_STORED_AS)
            .assertTextEquals("Nagios will store this as: see bINC-42/b")
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).performClick()

        assertEquals("the screen passes on what was typed", listOf("comment:see <b>INC-42</b>"), calls)
    }

    @Test
    fun `cancelling a form sends nothing and brings the buttons back`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.COMMENT).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("never mind")

        composeRule.onNodeWithTag(DetailTags.FORM_CANCEL).performClick()

        assertTrue(calls.isEmpty())
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.COMMENT).assertIsDisplayed()
    }

    @Test
    fun `the last result steps aside while a form is open, and returns with the buttons`() {
        show(service(hard).copy(action = ActionProgress.Confirmed(Command.ForceCheck(DISK_REF))))
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextEquals("Checked again.")

        composeRule.onNodeWithTag(DetailTags.COMMENT).performClick()
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertDoesNotExist()

        composeRule.onNodeWithTag(DetailTags.FORM_CANCEL).performClick()
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextEquals("Checked again.")
    }

    @Test
    fun `downtime starts at two hours from now, and asks for the server's clock as it opens`() {
        show(service(hard))

        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        assertEquals(listOf("loadServerClock"), calls)
        composeRule.onNodeWithTag(DetailTags.downtimePreset(120)).assertIsSelected()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW).assertTextContains("From now until", substring = true)
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsNotEnabled()

        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("patching")
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).performClick()

        assertEquals("downtime:patching|${DowntimeWindow.FromNow(Duration.ofHours(2))}", calls.last())
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertDoesNotExist()
    }

    @Test
    fun `each length offered is the length sent`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("x")

        assertEquals(listOf(30L, 60L, 120L, 240L, 480L, 1440L), PRESETS.map { it.toMinutes() })
        composeRule.onNodeWithTag(DetailTags.downtimePreset(30)).assertTextEquals("30 min").performClick()
        composeRule.onNodeWithTag(DetailTags.downtimePreset(1440)).assertTextEquals("24 h")
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).performClick()

        assertEquals("downtime:x|${DowntimeWindow.FromNow(Duration.ofMinutes(30))}", calls.last())
    }

    @Test
    fun `a custom window starts as now for two hours and is sent as a start and an end`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("x")

        composeRule.onNodeWithTag(DetailTags.DOWNTIME_START).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_CUSTOM).performClick()

        composeRule.onNodeWithTag(DetailTags.DOWNTIME_START).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_END).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW)
            .assertTextContains(formatWhen(T0), substring = true)
            .assertTextContains(formatWhen(T0.plusSeconds(7200)), substring = true)
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).performClick()

        assertEquals("downtime:x|${DowntimeWindow.Between(T0, T0.plusSeconds(7200))}", calls.last())
    }

    @Test
    fun `a window that is already over cannot be sent, and says why`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        composeRule.onNodeWithTag(DetailTags.FORM_COMMENT).performTextInput("x")
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_CUSTOM).performClick()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_INVALID).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsEnabled()

        // The form was left open for three hours: the window chosen then has passed.
        state = service(hard).copy(now = T0.plus(Duration.ofHours(3)))

        composeRule.onNodeWithTag(DetailTags.DOWNTIME_INVALID).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.FORM_SEND).assertIsNotEnabled()
    }

    @Test
    fun `the window is also shown as the server will be told it, once its clock is known`() {
        show(service(hard))
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_CUSTOM).performClick()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW_SERVER).assertDoesNotExist()

        state = service(hard).copy(
            serverClock = ServerClock(ServerDateFormat.EURO, ZoneOffset.ofHours(10), "09-10-2026 20:00:00"),
        )

        // T0 is 2026-10-09 10:00 UTC, so 20:00 on a server ten hours ahead.
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW_SERVER)
            .assertTextEquals(
                "Nagios will be told: 09-10-2026 20:00:00 to 09-10-2026 22:00:00 (its clock is UTC+10:00).",
            )
    }

    @Test
    fun `when the date order is not known the server's version is left out, not guessed`() {
        show(
            service(hard).copy(serverClock = ServerClock(format = null, ZoneOffset.UTC, "10-10-2026 10:00:00")),
        )
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).performClick()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_CUSTOM).performClick()

        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME_WINDOW_SERVER).assertDoesNotExist()
    }

    @Test
    fun `a scheduled downtime can be cancelled, after being asked`() {
        show(service(hard, downtimes = AnnotationSection(listOf(downtime(7), downtime(8)), T0)))

        composeRule.onNodeWithTag(DetailTags.cancelDowntime(8)).performClick()
        assertTrue("nothing is sent by the first tap", calls.isEmpty())
        composeRule.onNodeWithText("Cancel this downtime?").assertIsDisplayed()
        // Once in the list behind, once in the question: the dialog says which downtime it means.
        composeRule.onAllNodesWithText("maintenance 8", substring = true).assertCountEquals(2)

        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).performClick()

        assertEquals(listOf("cancelDowntime:8"), calls)
        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `keeping a downtime sends nothing`() {
        show(service(hard, downtimes = AnnotationSection(listOf(downtime(7)), T0)))
        composeRule.onNodeWithTag(DetailTags.cancelDowntime(7)).performClick()

        composeRule.onNodeWithTag(DetailTags.DIALOG_CANCEL).performClick()

        assertTrue(calls.isEmpty())
        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `a read-only user cannot cancel a downtime or schedule one`() {
        val listed = AnnotationSection(listOf(downtime(7)), T0)
        show(service(hard, downtimes = listed).copy(permission = CommandPermission.READ_ONLY))

        composeRule.onNodeWithTag(DetailTags.cancelDowntime(7)).assertIsNotEnabled()
        composeRule.onNodeWithTag(DetailTags.DOWNTIME).assertIsNotEnabled()
    }

    @Test
    fun `a downtime Nagios put somewhere else shows both windows and a way to cancel it`() {
        val ask = Command.ScheduleDowntime(DISK_REF, "patching", T0, T0.plusSeconds(7200))
        val scheduled = downtime(9).copy(start = T0.plusSeconds(3600), end = T0.plusSeconds(10800))
        show(service(hard).copy(action = ActionProgress.Mismatch(ask, scheduled)))

        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextContains("a different window", substring = true)
            .assertTextContains("Asked: ${formatWhen(ask.start)} to ${formatWhen(ask.end)}", substring = true)
            .assertTextContains(
                "Scheduled: ${formatWhen(scheduled.start)} to ${formatWhen(scheduled.end)}",
                substring = true,
            )
        composeRule.onNodeWithTag(DetailTags.MISMATCH_CANCEL).performClick()

        assertEquals(listOf("cancelDowntime:9"), calls)
    }

    @Test
    fun `no way to cancel is offered for a downtime that went where it was asked`() {
        val ask = Command.ScheduleDowntime(DISK_REF, "patching", T0, T0.plusSeconds(7200))
        show(service(hard).copy(action = ActionProgress.Confirmed(ask)))

        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextEquals("Downtime scheduled, for the window asked for.")
        composeRule.onNodeWithTag(DetailTags.MISMATCH_CANCEL).assertDoesNotExist()

        state = service(hard).copy(action = ActionProgress.Confirmed(Command.CancelDowntime(DISK_REF, 9)))
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextEquals("Downtime cancelled.")
    }

    @Test
    fun `the date order is asked with the server's own words, and each answer is passed on`() {
        val ask = Command.ScheduleDowntime(DISK_REF, "patching", T0, T0.plusSeconds(7200))
        show(service(hard).copy(action = ActionProgress.DateOrderNeeded(ask, "10-10-2026 09:00:00")))

        composeRule.onNodeWithText("Day first or month first?").assertIsDisplayed()
        composeRule.onNodeWithText("“10-10-2026 09:00:00”", substring = true)
            .assertTextContains("Nothing has been sent", substring = true)

        composeRule.onNodeWithTag(DetailTags.DATE_ORDER_MONTH_FIRST).performClick()
        composeRule.onNodeWithTag(DetailTags.DATE_ORDER_DAY_FIRST).performClick()
        composeRule.onNodeWithTag(DetailTags.DIALOG_CANCEL).performClick()

        assertEquals(listOf("dateOrder:US", "dateOrder:EURO", "dismissDateOrder"), calls)
    }

    @Test
    fun `removing an acknowledgement is asked about first`() {
        show(service(hard.copy(acknowledged = true)))

        composeRule.onNodeWithTag(DetailTags.REMOVE_ACKNOWLEDGEMENT).performClick()
        assertTrue("nothing is sent by the first tap", calls.isEmpty())
        composeRule.onNodeWithText("Remove the acknowledgement?").assertIsDisplayed()

        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).performClick()
        assertEquals(listOf("removeAcknowledgement"), calls)
        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `declining to remove an acknowledgement sends nothing`() {
        show(service(hard.copy(acknowledged = true)))
        composeRule.onNodeWithTag(DetailTags.REMOVE_ACKNOWLEDGEMENT).performClick()

        composeRule.onNodeWithTag(DetailTags.DIALOG_CANCEL).performClick()

        assertTrue(calls.isEmpty())
        composeRule.onNodeWithTag(DetailTags.DIALOG_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `while a command is on its way the buttons are off and it says so`() {
        val command = Command.ForceCheck(DISK_REF)
        show(service(hard).copy(action = ActionProgress.Sending(command)))

        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextEquals("Sending…")
        composeRule.onNodeWithTag(DetailTags.RECHECK).assertIsNotEnabled()

        state = service(hard).copy(action = ActionProgress.Sent(command))
        composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextContains("accepted it", substring = true)
        composeRule.onNodeWithTag(DetailTags.COMMENT).assertIsNotEnabled()
    }

    @Test
    fun `accepted is never called done, and done names what was done`() {
        val ack = Command.Acknowledge(DISK_REF, "on it")
        show(service(hard).copy(action = ActionProgress.NotConfirmed(ack)))

        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextContains("has not taken effect yet", substring = true)
        composeRule.onNodeWithTag(DetailTags.RECHECK).assertIsEnabled()

        val confirmed = mapOf(
            ack to "Acknowledged.",
            Command.RemoveAcknowledgement(DISK_REF) to "Acknowledgement removed.",
            Command.AddComment(DISK_REF, "x") to "Comment added.",
            Command.ForceCheck(DISK_REF) to "Checked again.",
        )
        confirmed.forEach { (command, words) ->
            state = service(hard).copy(action = ActionProgress.Confirmed(command))
            composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextEquals(words)
        }
    }

    @Test
    fun `each refusal is told apart, and Nagios' own words are shown where they add something`() {
        val command = Command.ForceCheck(DISK_REF)
        show(service(hard))
        val expected = listOf(
            ActionProgress.Refused(command, Refusal.READ_ONLY, "") to "read-only",
            ActionProgress.Refused(command, Refusal.NOT_AUTHORISED, "") to "may not do that here",
            ActionProgress.Refused(command, Refusal.INVALID, "Comment was not entered") to "Comment was not entered",
            ActionProgress.Refused(command, Refusal.INVALID, "") to "did not say why",
            ActionProgress.Refused(command, Refusal.COMMANDS_DISABLED, "") to "external commands are switched off",
            ActionProgress.Refused(command, Refusal.COULD_NOT_WRITE, "") to "could not record the command",
            ActionProgress.Refused(command, Refusal.AUTHENTICATION_DISABLED, "") to "authentication is switched off",
        )

        expected.forEach { (progress, words) ->
            state = service(hard).copy(action = progress)
            composeRule.onNodeWithTag(DetailTags.PROGRESS).assertTextContains(words, substring = true)
        }
    }

    @Test
    fun `not sent says nothing changed, and unknown says to look before trying again`() {
        val command = Command.ForceCheck(DISK_REF)
        val timeout = NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "")
        show(service(hard).copy(action = ActionProgress.NotSent(command, StatusError.Nagios(NagiosError.Http(502)))))

        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextContains("Not sent. Nothing was changed.", substring = true)
            .assertTextContains("HTTP 502", substring = true)

        state = service(hard).copy(action = ActionProgress.Unknown(command, timeout))
        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextContains("may or may not have reached Nagios", substring = true)
            .assertTextContains("did not answer in time", substring = true)

        state = service(hard).copy(action = ActionProgress.Unknown(command, null))
        composeRule.onNodeWithTag(DetailTags.PROGRESS)
            .assertTextContains("before trying again", substring = true)
    }

    // Real text measurement, at phone width: the longest button label and message must wrap, not run off.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(application = android.app.Application::class, qualifiers = "w360dp-h3000dp-xhdpi")
    fun `the actions and what became of them stay inside a phone's width`() {
        val unknown = ActionProgress.Unknown(
            Command.ForceCheck(DISK_REF),
            NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, ""),
        )
        show(service(hard.copy(acknowledged = true)).copy(permission = CommandPermission.READ_ONLY, action = unknown))

        val screen = composeRule.onRoot().getUnclippedBoundsInRoot().width
        val tags = listOf(
            DetailTags.REMOVE_ACKNOWLEDGEMENT,
            DetailTags.RECHECK,
            DetailTags.COMMENT,
            DetailTags.READ_ONLY,
            DetailTags.PROGRESS,
        )
        tags.forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag runs off the screen: ${bounds.right} of $screen", bounds.right <= screen)
            assertTrue("$tag has collapsed: ${bounds.width}", bounds.width > 40.dp)
        }
    }
}
