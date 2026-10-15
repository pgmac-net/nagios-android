// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.notify.Notice.Change
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.theme.LightStateColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The buttons on a problem's notification: what they carry, who may press them, and how the answer is said. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationButtonsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private var allowed = true
    private val notifier = AndroidNotifier(context, NotificationPermission { allowed })

    private val home = profile(1, "Home")
    private val office = profile(2, "Office")
    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")

    @Test
    fun `a problem can be acknowledged or rechecked from its notification, and nothing else can`() {
        raise(disk, ServiceState.CRITICAL)
        raiseHost("db01", HostState.DOWN)
        deliver(
            home,
            post = listOf(
                Notice.Recovered(load),
                Notice.Summary(2, first = true),
                Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false),
            ),
        )

        assertEquals(listOf("Acknowledge", "Recheck"), problem(disk).buttons())
        assertEquals(listOf("Acknowledge", "Recheck"), problem(ObjectRef("db01")).buttons())
        assertTrue("a recovery has nothing to acknowledge", problem(load).buttons().isEmpty())
        assertTrue(shown(home, AndroidNotifier.ID_SUMMARY).buttons().isEmpty())
        assertTrue(shown(home, AndroidNotifier.ID_OUTAGE).buttons().isEmpty())
        assertTrue(shown(home, AndroidNotifier.ID_GROUP).buttons().isEmpty())
    }

    @Test
    fun `a user Nagios takes no commands from gets no buttons`() {
        raise(disk, ServiceState.CRITICAL, mayAct = false)

        assertTrue(problem(disk).buttons().isEmpty())
    }

    @Test
    fun `a button says what to do, to what and on which Nagios, and only this app can be sent it`() {
        raise(disk, ServiceState.CRITICAL, output = "DISK CRITICAL - 3% free")
        raise(disk, ServiceState.CRITICAL, output = "DISK CRITICAL - 3% free", profile = office)

        val acknowledge = shadowOf(problem(disk).actions[0].actionIntent)
        val recheck = shadowOf(problem(disk, office).actions[1].actionIntent)

        assertEquals(NotificationActionReceiver::class.java.name, acknowledge.savedIntent.component?.className)
        assertEquals(context.packageName, acknowledge.savedIntent.component?.packageName)
        assertTrue(acknowledge.isBroadcast)
        assertTrue("the intent cannot be altered", acknowledge.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val asked = checkNotNull(ActionRequest.from(acknowledge.savedIntent))
        assertEquals(Triple(home.id, disk, NoticeAction.ACKNOWLEDGE), Triple(asked.profileId, asked.ref, asked.action))
        assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", asked.title)
        assertEquals("DISK CRITICAL - 3% free", asked.text)
        assertEquals("problems_critical", asked.channelId)
        val other = checkNotNull(ActionRequest.from(recheck.savedIntent))
        assertEquals(
            "the same service on another Nagios is another button",
            Triple(office.id, disk, NoticeAction.RECHECK),
            Triple(other.profileId, other.ref, other.action),
        )
    }

    @Test
    fun `the buttons cannot be pressed on a locked phone - Android asks for the unlock first`() {
        raise(disk, ServiceState.CRITICAL)

        val buttons = problem(disk).actions
        assertEquals(2, buttons.size)
        assertTrue("acknowledge", buttons[0].isAuthenticationRequired)
        assertTrue("recheck too: it is this profile's credentials either way", buttons[1].isAuthenticationRequired)
    }

    @Test
    fun `a press that is refused for a locked phone sends nothing, says to unlock, and is heard`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.ACKNOWLEDGE)

        notifier.needsUnlock(request)

        val shown = problem(disk)
        assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", shown.title())
        assertEquals("Unlock the phone to do this. Nothing was sent.", shown.text())
        assertEquals(listOf("Acknowledge", "Recheck"), shown.buttons())
        assertTrue(shown.actions.all { it.isAuthenticationRequired })
        assertFalse("a refusal nobody hears looks, on a watch, like nothing happened", shown.isSilent())
    }

    @Test
    fun `a locked phone is one that needs unlocking, and a phone with no lock is not`() {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))

        keyguard.setIsDeviceLocked(false)
        assertFalse(NotificationActionReceiver.isLocked(context))

        keyguard.setIsDeviceLocked(true)
        assertTrue(NotificationActionReceiver.isLocked(context))
    }

    @Test
    fun `from Android 12 a press that Android delivers from a locked phone is a watch's, and is let through`() {
        val android12 = Build.VERSION_CODES.S

        assertFalse(NotificationActionReceiver.refusesWhileLocked(android12, locked = true))
        assertFalse(NotificationActionReceiver.refusesWhileLocked(android12 + 6, locked = true))
        assertFalse(NotificationActionReceiver.refusesWhileLocked(android12, locked = false))
    }

    @Test
    fun `before Android 12 nothing else guards the lock screen, so a locked phone is refused`() {
        val android11 = Build.VERSION_CODES.R

        assertTrue(NotificationActionReceiver.refusesWhileLocked(android11, locked = true))
        assertTrue(NotificationActionReceiver.refusesWhileLocked(Build.VERSION_CODES.O, locked = true))
        assertFalse(
            "an unlocked phone is never refused",
            NotificationActionReceiver.refusesWhileLocked(android11, false),
        )
    }

    @Test
    fun `an answer is heard when it did not work, and not when it did`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.ACKNOWLEDGE)
        val command = Command.Acknowledge(disk, "on it")

        notifier.acted(request, ActionProgress.Refused(command, Refusal.READ_ONLY, ""))
        assertFalse(problem(disk).isSilent())

        notifier.acted(request, ActionProgress.NotSent(command, StatusError.InvalidUrl))
        assertFalse(problem(disk).isSilent())

        notifier.acted(request, ActionProgress.Confirmed(command))
        assertTrue("they pressed it; no need to be called over to hear it worked", problem(disk).isSilent())
    }

    @Test
    fun `a pressed button says it is on its way, in the problem's own place, with no button to press twice`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.ACKNOWLEDGE)

        notifier.acting(request)

        val shown = problems().single().notification
        assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", shown.title())
        assertEquals("Acknowledging\u2026", shown.text())
        assertTrue(shown.buttons().isEmpty())
        assertTrue("it cannot be swiped away half way", shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("the person pressed it: no need to call them over", shown.isSilent())
    }

    @Test
    fun `a confirmed acknowledgement says so and clears itself in a minute`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.ACKNOWLEDGE)
        notifier.acting(request)

        notifier.acted(request, ActionProgress.Confirmed(Command.Acknowledge(disk, "on it")))

        val shown = problems().single().notification
        assertEquals("Acknowledged.", shown.text())
        assertTrue(shown.buttons().isEmpty())
        assertEquals(TimeUnit.MINUTES.toMillis(1), shown.timeoutAfter)
        assertEquals(0, shown.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun `a recheck leaves the problem a problem, with its buttons back`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.RECHECK)
        notifier.acting(request)
        assertEquals("Asking for a check\u2026", problem(disk).text())

        notifier.acted(request, ActionProgress.Confirmed(Command.ForceCheck(disk)))

        val shown = problem(disk)
        assertTrue(shown.text().orEmpty().startsWith("Checked again."))
        assertEquals(listOf("Acknowledge", "Recheck"), shown.buttons())
        assertEquals(0L, shown.timeoutAfter)
    }

    @Test
    fun `a command that did not work says why on the notification, and can be tried again from it`() {
        raise(disk, ServiceState.CRITICAL)
        val request = request(disk, NoticeAction.ACKNOWLEDGE)
        val command = Command.Acknowledge(disk, "on it")
        val answers = listOf(
            ActionProgress.Refused(command, Refusal.READ_ONLY, ""),
            ActionProgress.Refused(command, Refusal.INVALID, "no such host"),
            ActionProgress.NotSent(command, StatusError.InvalidUrl),
            ActionProgress.NotConfirmed(command),
            ActionProgress.Unknown(command, null),
        )

        val said = answers.map { answer ->
            notifier.acted(request, answer)
            val shown = problem(disk)
            assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", shown.title())
            assertEquals(listOf("Acknowledge", "Recheck"), shown.buttons())
            checkNotNull(shown.text())
        }

        assertEquals("each in its own words", answers.size, said.toSet().size)
        assertTrue(said[0].contains("read-only"))
        assertTrue(said[1].contains("no such host"))
    }

    @Test
    fun `the answer is said even if the notification was swiped away meanwhile, group and all`() {
        val request = request(disk, NoticeAction.RECHECK)
        assertTrue(active().isEmpty())

        notifier.acted(request, ActionProgress.Confirmed(Command.ForceCheck(disk)))

        assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", problem(disk).title())
        assertEquals("Home", shown(home, AndroidNotifier.ID_GROUP).title())
    }

    @Test
    fun `without permission to post, a button's answer is not posted either`() {
        allowed = false

        notifier.acting(request(disk, NoticeAction.ACKNOWLEDGE))
        notifier.acted(request(disk, NoticeAction.ACKNOWLEDGE), ActionProgress.NotConfirmed(Command.ForceCheck(disk)))

        assertTrue(active().isEmpty())
    }

    @Test
    fun `a request that is not whole is nobody's request`() {
        val whole = request(disk, NoticeAction.ACKNOWLEDGE)
        fun intent() = shadowOf(whole.toPendingIntent(context)).savedIntent.let(::Intent)

        assertEquals(whole, ActionRequest.from(intent()))
        assertEquals(whole, ActionRequest.from(whole.toData()))
        assertNull("something it cannot do", ActionRequest.from(intent().putExtra("action", "DELETE_EVERYTHING")))
        assertNull("no host", ActionRequest.from(intent().putExtra("host", "")))
        assertNull("no profile", ActionRequest.from(intent().apply { removeExtra("profile") }))
        assertNull("a channel that is not ours", ActionRequest.from(intent().putExtra("channel", "elsewhere")))
        assertNull(ActionRequest.from(Intent()))
    }

    private fun deliver(profile: Profile, post: List<Notice>, mayAct: Boolean = true) =
        notifier.deliver(profile, Decision(post, emptyList(), false, AnnouncedState()), mayAct)

    /** What a button on the critical "Disk /" notification of Home would carry. */
    private fun request(ref: ObjectRef, action: NoticeAction) = ActionRequest(
        profileId = home.id,
        profileName = home.name,
        ref = ref,
        action = action,
        channelId = "problems_critical",
        title = "\uD83D\uDD34 CRITICAL: Disk / on web01",
        text = "plugin output",
        color = LightStateColors.critical.toArgb(),
    )

    private fun Notification.buttons(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun raise(
        ref: ObjectRef,
        state: ServiceState,
        output: String = "plugin output",
        profile: Profile = home,
        mayAct: Boolean = true,
    ) {
        val service =
            ServiceStatus(ref.hostName, checkNotNull(ref.description), state, CheckStatus(pluginOutput = output))
        val host = HostStatus(ref.hostName, HostState.UP, CheckStatus())
        val raised = Notice.Raised(ref, classify(listOf(host), listOf(service)), Change.NEW)
        deliver(profile, post = listOf(raised), mayAct = mayAct)
    }

    private fun raiseHost(name: String, state: HostState) {
        val host = HostStatus(name, state, CheckStatus(pluginOutput = "no reply"))
        deliver(home, post = listOf(Notice.Raised(ObjectRef(name), classify(listOf(host), emptyList()), Change.NEW)))
    }

    private fun classify(hosts: List<HostStatus>, services: List<ServiceStatus>): Problem =
        ProblemClassifier.classify(StatusSnapshot(hosts, services, T0)).unhandled.single()

    private fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun problems(): List<StatusBarNotification> = active().filter { it.id == AndroidNotifier.ID_PROBLEM }

    private fun problem(ref: ObjectRef, profile: Profile = home): Notification =
        problems().single { it.tag == AndroidNotifier.problemTag(profile.id, ref) }.notification

    private fun shown(profile: Profile, id: Int): Notification =
        active().single { it.id == id && it.tag == AndroidNotifier.profileTag(profile.id) }.notification

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** How a member of a group is made silent on Android 8 and later: it leaves the sound to the group's entry. */
    private fun Notification.isSilent(): Boolean = groupAlertBehavior == NotificationCompat.GROUP_ALERT_SUMMARY

    private fun profile(id: Long, name: String) = Profile(
        id = id,
        name = name,
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        hasPassword = true,
        accessClientId = "",
        hasAccessClientSecret = false,
        customHeaderNames = emptyList(),
        allowCleartext = false,
    )
}
