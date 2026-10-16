// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each thing worth saying, as the notification Android is handed. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class AndroidNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private var allowed = true
    private var notifier = AndroidNotifier(context, NotificationPermission { allowed })

    private val home = profile(1, "Home")
    private val office = profile(2, "Office")
    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")

    // ---------------------------------------------------------------- channels

    @Test
    fun `the kinds of notification are there for the person to tune, each at its own importance`() {
        assertTrue("none before there is anything to say", manager.notificationChannels.isEmpty())

        raise(disk, ServiceState.CRITICAL)

        assertEquals(
            mapOf(
                "problems_critical" to NotificationManager.IMPORTANCE_HIGH,
                "problems_warning" to NotificationManager.IMPORTANCE_DEFAULT,
                "problems_unknown" to NotificationManager.IMPORTANCE_DEFAULT,
                "recovered" to NotificationManager.IMPORTANCE_LOW,
                "unreachable" to NotificationManager.IMPORTANCE_DEFAULT,
                "summaries" to NotificationManager.IMPORTANCE_DEFAULT,
                "loud_alert" to NotificationManager.IMPORTANCE_LOW,
            ),
            manager.notificationChannels.associate { it.id to it.importance },
        )
        assertEquals("Down and critical", manager.getNotificationChannel("problems_critical").name.toString())
    }

    @Test
    fun `each severity goes to the channel a person would expect to find it on`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)
        raise(ObjectRef("web01", "Odd"), ServiceState.UNKNOWN)
        raiseHost("db01", HostState.DOWN)
        raiseHost("db02", HostState.UNREACHABLE)

        assertEquals("problems_critical", problem(disk).channelId)
        assertEquals("problems_warning", problem(load).channelId)
        assertEquals("problems_unknown", problem(ObjectRef("web01", "Odd")).channelId)
        assertEquals("problems_critical", problem(ObjectRef("db01")).channelId)
        assertEquals("problems_critical", problem(ObjectRef("db02")).channelId)
    }

    // ---------------------------------------------------------------- colour

    @Test
    fun `a problem's icon is tinted with its state's colour, as the app's own screens show it`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)
        raise(ObjectRef("web01", "Odd"), ServiceState.UNKNOWN)
        raiseHost("db01", HostState.DOWN)
        raiseHost("db02", HostState.UNREACHABLE)

        val light = LightStateColors
        assertEquals(light.critical.toArgb(), problem(disk).color)
        assertEquals(light.warning.toArgb(), problem(load).color)
        assertEquals(light.unknown.toArgb(), problem(ObjectRef("web01", "Odd")).color)
        assertEquals(light.critical.toArgb(), problem(ObjectRef("db01")).color)
        assertEquals(light.critical.toArgb(), problem(ObjectRef("db02")).color)
    }

    @Test
    fun `a recovery is tinted with the colour of OK`() {
        raise(disk, ServiceState.CRITICAL)

        deliver(home, post = listOf(Notice.Recovered(disk)))

        assertEquals(LightStateColors.ok.toArgb(), problem(disk).color)
    }

    @Test
    @Config(application = android.app.Application::class, qualifiers = "night")
    fun `on a dark phone the dark variants are used`() {
        raise(disk, ServiceState.WARNING)

        assertEquals(DarkStateColors.warning.toArgb(), problem(disk).color)
    }

    @Test
    fun `a summary, an outage and the group's entry have no state colour to wear`() {
        deliver(home, post = listOf(Notice.Summary(count = 6, first = true)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))

        assertEquals(Notification.COLOR_DEFAULT, shown(home, AndroidNotifier.ID_SUMMARY).color)
        assertEquals(Notification.COLOR_DEFAULT, shown(home, AndroidNotifier.ID_OUTAGE).color)
        assertEquals(Notification.COLOR_DEFAULT, shown(home, AndroidNotifier.ID_GROUP).color)
    }

    @Test
    fun `every channel can be made without posting, for the page that lists them`() {
        assertTrue(manager.notificationChannels.isEmpty())

        NotifyChannel.createAll(context)
        NotifyChannel.createAll(context)

        assertEquals(NotifyChannel.entries.map { it.id }.toSet(), manager.notificationChannels.map { it.id }.toSet())
    }

    // ---------------------------------------------------------------- problems

    @Test
    fun `a new problem says what, where and on which Nagios, with the first line of its output`() {
        raise(disk, ServiceState.CRITICAL, output = "DISK CRITICAL - free space: / 3%\nsecond line")

        val shown = problem(disk)
        assertEquals("\uD83D\uDD34 CRITICAL: Disk / on web01", shown.title())
        assertEquals("DISK CRITICAL - free space: / 3%", shown.text())
        assertEquals("Home", shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertFalse("a new problem may interrupt", shown.isSilent())
    }

    @Test
    fun `a host is named by itself, down or unreachable`() {
        raiseHost("db01", HostState.DOWN)
        raiseHost("db02", HostState.UNREACHABLE)

        assertEquals("\uD83D\uDD34 DOWN: db01", problem(ObjectRef("db01")).title())
        assertEquals("\uD83D\uDD34 UNREACHABLE: db02", problem(ObjectRef("db02")).title())
    }

    @Test
    fun `each state has its own circle in the title, and a summary and the group's entry have none`() {
        raise(disk, ServiceState.WARNING)
        raise(ObjectRef("web01", "Odd"), ServiceState.UNKNOWN)

        assertEquals("\uD83D\uDFE1 WARNING: Disk / on web01", problem(disk).title())
        assertEquals("\uD83D\uDFE0 UNKNOWN: Odd on web01", problem(ObjectRef("web01", "Odd")).title())

        deliver(home, post = listOf(Notice.Summary(count = 6, first = true)))
        assertEquals("6 problems on Home", shown(home, AndroidNotifier.ID_SUMMARY).title())
        assertEquals("Home", shown(home, AndroidNotifier.ID_GROUP).title())
    }

    @Test
    fun `the chosen set decides the emoji, and one that has none adds nothing, not even a space`() {
        val expected = mapOf(
            EmojiSet.SIGNS to listOf("\uD83D\uDEA8 CRITICAL: Disk / on web01", "\u26D4 DOWN: db01"),
            EmojiSet.SQUARES to listOf("\uD83D\uDFE5 CRITICAL: Disk / on web01", "\uD83D\uDFE5 DOWN: db01"),
            EmojiSet.FACES to listOf("\uD83D\uDE31 CRITICAL: Disk / on web01", "\uD83D\uDE31 DOWN: db01"),
            EmojiSet.NONE to listOf("CRITICAL: Disk / on web01", "DOWN: db01"),
        )
        expected.forEach { (set, titles) ->
            notifier = AndroidNotifier(context, { allowed }, emojiSet = { set })
            raise(disk, ServiceState.CRITICAL)
            raiseHost("db01", HostState.DOWN)
            assertEquals(set.name, titles[0], problem(disk).title())
            assertEquals(set.name, titles[1], problem(ObjectRef("db01")).title())
        }
    }

    @Test
    fun `a recovery and an outage take the set's emoji too, and none for the set with none`() {
        notifier = AndroidNotifier(context, { allowed }, emojiSet = { EmojiSet.FACES })
        deliver(home, post = listOf(Notice.Recovered(disk)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))
        assertEquals("\uD83D\uDE0C Recovered: Disk / on web01", problem(disk).title())
        assertEquals("\uD83D\uDCE1 Can\u2019t check Home", shown(home, AndroidNotifier.ID_OUTAGE).title())

        notifier = AndroidNotifier(context, { allowed }, emojiSet = { EmojiSet.NONE })
        deliver(home, post = listOf(Notice.Recovered(disk)))
        assertEquals("Recovered: Disk / on web01", problem(disk).title())
    }

    // ------------------------------------------------------------ large icon

    @Test
    fun `problems, recoveries and an outage have a large icon, and a summary and the group's entry have none`() {
        raise(disk, ServiceState.CRITICAL)
        deliver(home, post = listOf(Notice.Recovered(load)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))
        deliver(home, post = listOf(Notice.Summary(count = 6, first = true)))

        assertTrue(problem(disk).hasLargeIcon())
        assertTrue(problem(load).hasLargeIcon())
        assertTrue(shown(home, AndroidNotifier.ID_OUTAGE).hasLargeIcon())
        assertFalse(shown(home, AndroidNotifier.ID_SUMMARY).hasLargeIcon())
        assertFalse(shown(home, AndroidNotifier.ID_GROUP).hasLargeIcon())
    }

    @Test
    fun `with the setting off no notification has one`() {
        notifier = AndroidNotifier(context, { allowed }, largeIcon = { false })
        raise(disk, ServiceState.CRITICAL)
        deliver(home, post = listOf(Notice.Recovered(load)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))

        assertFalse(problem(disk).hasLargeIcon())
        assertFalse(problem(load).hasLargeIcon())
        assertFalse(shown(home, AndroidNotifier.ID_OUTAGE).hasLargeIcon())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the large icon is drawn in the state's colour with the glyph in the colour drawn on it`() {
        val critical = LightStateColors.critical.toArgb()
        val bitmap = LargeIcon.render(
            context,
            critical,
            net.pgmac.nagwatch.R.drawable.ic_tab_problems,
            0xFFFFFFFF.toInt(),
        )

        assertEquals("near the edge is the state's colour", critical, bitmap.getPixel(bitmap.width / 2, 2))
        assertEquals(
            "a corner is outside the circle",
            0,
            android.graphics.Color.alpha(bitmap.getPixel(0, 0)),
        )
    }

    @Test
    fun `what a plugin printed is cut to one line of a sensible length, and nothing is shown for nothing`() {
        raise(disk, ServiceState.CRITICAL, output = "x".repeat(5_000))
        raise(load, ServiceState.WARNING, output = "")

        assertEquals(300, problem(disk).text()?.length)
        assertNull(problem(load).text())
    }

    @Test
    fun `getting worse replaces the problem's notification, says so, and may interrupt`() {
        raise(disk, ServiceState.WARNING)

        raise(disk, ServiceState.CRITICAL, change = Change.WORSE)

        val shown = problems().single()
        assertEquals("\uD83D\uDD34 Now CRITICAL: Disk / on web01", shown.notification.title())
        assertEquals("problems_critical", shown.notification.channelId)
        assertFalse(shown.notification.isSilent())
    }

    @Test
    fun `an update replaces it too, without interrupting`() {
        raise(disk, ServiceState.CRITICAL)

        raise(disk, ServiceState.WARNING, change = Change.UPDATED)

        val shown = problems().single().notification
        assertEquals("\uD83D\uDFE1 WARNING: Disk / on web01", shown.title())
        assertTrue(shown.isSilent())
    }

    @Test
    fun `a recovery takes the problem's place, silently, and clears itself after an hour`() {
        raise(disk, ServiceState.CRITICAL)
        raiseHost("db01", HostState.DOWN)

        deliver(home, post = listOf(Notice.Recovered(disk), Notice.Recovered(ObjectRef("db01"))))

        assertEquals(2, problems().size)
        val service = problem(disk)
        assertEquals("\uD83D\uDFE2 Recovered: Disk / on web01", service.title())
        assertEquals("recovered", service.channelId)
        assertTrue(service.isSilent())
        assertEquals(TimeUnit.HOURS.toMillis(1), service.timeoutAfter)
        assertEquals("\uD83D\uDFE2 Back up: db01", problem(ObjectRef("db01")).title())
    }

    @Test
    fun `a problem's own notification does not clear itself`() {
        raise(disk, ServiceState.CRITICAL)

        assertEquals(0L, problem(disk).timeoutAfter)
    }

    @Test
    fun `being dealt with takes down that problem's notification and no other`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)

        deliver(home, remove = listOf(disk))

        assertEquals(listOf(AndroidNotifier.problemTag(home.id, load)), problems().map { it.tag })
    }

    @Test
    fun `the same object on two Nagios servers is two notifications`() {
        raise(disk, ServiceState.CRITICAL, profile = home)
        raise(disk, ServiceState.CRITICAL, profile = office)

        assertEquals(2, problems().size)
        deliver(home, remove = listOf(disk))
        assertEquals("Office", problems().single().notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun `a host and a service on it are told apart, as are names that only differ in where they are split`() {
        val tags = listOf(ObjectRef("web01"), ObjectRef("web01", ""), ObjectRef("web", "01"), ObjectRef("web01", "x"))
            .map { AndroidNotifier.problemTag(1, it) }

        // A host and a service with an empty name are the same thing to Nagios too; the rest differ.
        assertEquals(3, tags.toSet().size)
        assertNotEquals(AndroidNotifier.problemTag(1, disk), AndroidNotifier.problemTag(11, disk))
    }

    // ---------------------------------------------------------------- summaries and outages

    @Test
    fun `the first summary counts the problems and names the Nagios`() {
        deliver(home, post = listOf(Notice.Summary(count = 6, first = true)))
        deliver(office, post = listOf(Notice.Summary(count = 1, first = true)))

        assertEquals("6 problems on Home", shown(home, AndroidNotifier.ID_SUMMARY).title())
        assertEquals("1 problem on Office", shown(office, AndroidNotifier.ID_SUMMARY).title())
        assertEquals("summaries", shown(home, AndroidNotifier.ID_SUMMARY).channelId)
    }

    @Test
    fun `a later summary says they are new or worse, and replaces the last one`() {
        deliver(home, post = listOf(Notice.Summary(count = 6, first = true)))

        deliver(home, post = listOf(Notice.Summary(count = 7, first = false)))

        assertEquals("7 new or worse problems on Home", shown(home, AndroidNotifier.ID_SUMMARY).title())
        assertEquals(1, active().count { it.id == AndroidNotifier.ID_SUMMARY })
    }

    @Test
    fun `an outage names the Nagios and says what is wrong in its own words for each cause`() {
        val sentences = OutageCause.entries.map { cause ->
            deliver(home, post = listOf(Notice.Outage(cause, StatusError.InvalidUrl, quiet = false)))
            val shown = shown(home, AndroidNotifier.ID_OUTAGE)
            assertEquals("\uD83D\uDCE1 Can\u2019t check Home", shown.title())
            assertEquals("unreachable", shown.channelId)
            assertFalse(shown.isSilent())
            checkNotNull(shown.text())
        }

        assertEquals("one sentence per cause", OutageCause.entries.size, sentences.toSet().size)
        assertEquals("and one notification", 1, active().count { it.id == AndroidNotifier.ID_OUTAGE })
    }

    @Test
    fun `a change of cause does not interrupt again, and the next good poll takes the outage down`() {
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))

        deliver(home, post = listOf(Notice.Outage(OutageCause.SERVER_ERROR, StatusError.InvalidUrl, quiet = true)))
        assertTrue(shown(home, AndroidNotifier.ID_OUTAGE).isSilent())

        deliver(home, removeOutage = true)
        assertTrue(active().isEmpty())
    }

    // ---------------------------------------------------------------- the group

    @Test
    fun `a profile's notifications share a group, which is there while they are and gone when they are`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)

        val entry = shown(home, AndroidNotifier.ID_GROUP)
        assertTrue(entry.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(setOf(AndroidNotifier.group(home.id)), active().map { it.notification.group }.toSet())
        assertTrue("the group's entry never makes a sound of its own", entry.isSilentGroupEntry())

        deliver(home, remove = listOf(disk))
        assertEquals(1, active().count { it.id == AndroidNotifier.ID_GROUP })

        deliver(home, remove = listOf(load))
        assertTrue(active().isEmpty())
    }

    @Test
    fun `two profiles have two groups, and each group goes when its own last member does`() {
        raise(disk, ServiceState.CRITICAL, profile = home)
        raise(disk, ServiceState.CRITICAL, profile = office)

        assertEquals(2, active().map { it.notification.group }.toSet().size)
        assertEquals(problem(disk, home).group, shown(home, AndroidNotifier.ID_GROUP).group)
        assertEquals(problem(disk, office).group, shown(office, AndroidNotifier.ID_GROUP).group)
        assertNotEquals(problem(disk, home).group, problem(disk, office).group)

        deliver(home, remove = listOf(disk))

        assertEquals(
            "Home has nothing left to show; Office is untouched",
            listOf(AndroidNotifier.profileTag(office.id)),
            active().filter { it.id == AndroidNotifier.ID_GROUP }.map { it.tag },
        )
        assertEquals(1, problems().size)
    }

    @Test
    fun `the group goes with its last member even while Android still lists what was just taken down`() {
        raise(disk, ServiceState.CRITICAL)
        val notYetCaughtUp = active()
        val onAPhone = AndroidNotifier(context, { allowed }, { notYetCaughtUp })

        onAPhone.deliver(home, Decision(remove = listOf(disk), state = AnnouncedState()))

        assertTrue("nothing left, and no empty group either", active().isEmpty())
    }

    @Test
    fun `the group is there for a first member even before Android lists it`() {
        val onAPhone = AndroidNotifier(context, { allowed }, { emptyList() })

        onAPhone.deliver(home, Decision(post = listOf(Notice.Summary(2, first = true)), state = AnnouncedState()))

        assertEquals(1, active().count { it.id == AndroidNotifier.ID_GROUP })
    }

    @Test
    fun `taking one down leaves the group for the members still showing, whatever Android lists`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)
        val notYetCaughtUp = active()
        val onAPhone = AndroidNotifier(context, { allowed }, { notYetCaughtUp })

        onAPhone.deliver(home, Decision(remove = listOf(disk), state = AnnouncedState()))

        assertEquals(1, active().count { it.id == AndroidNotifier.ID_GROUP })
        assertEquals(1, problems().size)
    }

    // ---------------------------------------------------------------- clearing and sweeping

    @Test
    fun `clearing a profile takes down its problems, summary, outage and group, and nobody else's`() {
        raise(disk, ServiceState.CRITICAL, profile = home)
        deliver(home, post = listOf(Notice.Summary(3, first = true)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))
        raise(disk, ServiceState.CRITICAL, profile = office)

        notifier.clear(home.id)

        val left = active()
        assertTrue(left.isNotEmpty())
        assertEquals(
            "only Office's problem and its group's entry",
            setOf(AndroidNotifier.group(office.id)),
            left.map { it.notification.group }.toSet(),
        )
        assertEquals(2, left.size)
    }

    @Test
    fun `clearing a profile that has nothing showing is harmless`() {
        raise(disk, ServiceState.CRITICAL, profile = office)

        notifier.clear(home.id)

        assertEquals(2, active().size)
    }

    @Test
    fun `even a list that has not caught up cannot leave the summary, the outage or the group's entry`() {
        raise(disk, ServiceState.CRITICAL)
        deliver(home, post = listOf(Notice.Summary(3, first = true)))
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))
        val onAPhone = AndroidNotifier(context, { allowed }, { emptyList() })

        onAPhone.clear(home.id)

        assertEquals(
            "what is known by identity goes whatever Android lists",
            0,
            active().count {
                it.id == AndroidNotifier.ID_SUMMARY || it.id == AndroidNotifier.ID_OUTAGE ||
                    it.id == AndroidNotifier.ID_GROUP
            },
        )
    }

    @Test
    fun `clearing takes the problems down even when Android lists them late`() {
        raise(disk, ServiceState.CRITICAL)
        raise(load, ServiceState.WARNING)
        val listed = active()
        val onAPhone = AndroidNotifier(context, { allowed }, { listed })

        onAPhone.clear(home.id)

        assertTrue(active().isEmpty())
    }

    @Test
    fun `sweeping keeps what is being watched and takes down the rest, including profiles that no longer exist`() {
        val gone = profile(99, "Gone")
        raise(disk, ServiceState.CRITICAL, profile = home)
        raise(disk, ServiceState.CRITICAL, profile = office)
        raise(disk, ServiceState.CRITICAL, profile = gone)
        deliver(gone, post = listOf(Notice.Summary(2, first = true)))

        notifier.sweep(setOf(home.id))

        assertEquals(setOf(AndroidNotifier.group(home.id)), active().map { it.notification.group }.toSet())
        assertEquals(2, active().size)
    }

    @Test
    fun `sweeping with nothing watched takes down everything of Nagwatch's`() {
        raise(disk, ServiceState.CRITICAL, profile = home)
        raise(disk, ServiceState.CRITICAL, profile = office)

        notifier.sweep(emptySet())

        assertTrue(active().isEmpty())
    }

    @Test
    fun `sweeping leaves alone what it cannot attribute to a profile`() {
        raise(disk, ServiceState.CRITICAL, profile = office)
        manager.notify(
            77,
            NotificationCompat.Builder(context, "x").setSmallIcon(net.pgmac.nagwatch.R.drawable.ic_check).build(),
        )

        notifier.sweep(emptySet())

        assertEquals(listOf(77), active().map { it.id })
    }

    @Test
    fun `a group key says which profile it is for, and nothing else does`() {
        assertEquals(7L, AndroidNotifier.profileOf(AndroidNotifier.group(7)))
        assertNull(AndroidNotifier.profileOf(null))
        assertNull(AndroidNotifier.profileOf("other"))
        assertNull(AndroidNotifier.profileOf("profile-x"))
        assertNull(AndroidNotifier.profileOf("profile-"))
        assertNull("a bare number is not ours", AndroidNotifier.profileOf("7"))
        assertNull(AndroidNotifier.profileOf("other-profile-7"))
    }

    // ---------------------------------------------------------------- permission

    @Test
    fun `without Android's permission nothing is posted, and nothing is set up to post it`() {
        allowed = false

        raise(disk, ServiceState.CRITICAL)
        deliver(home, post = listOf(Notice.Summary(3, first = true)))

        assertTrue(active().isEmpty())
        assertTrue(manager.notificationChannels.isEmpty())
    }

    @Test
    fun `what was showing is still taken down after the permission is lost`() {
        raise(disk, ServiceState.CRITICAL)
        deliver(home, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))
        allowed = false

        deliver(home, remove = listOf(disk), removeOutage = true)

        assertTrue(active().isEmpty())
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun `a tap opens what the notification was about`() {
        raise(disk, ServiceState.CRITICAL)
        raiseHost("db01", HostState.DOWN)
        deliver(
            home,
            post = listOf(
                Notice.Summary(2, first = true),
                Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false),
            ),
        )
        deliver(home, post = listOf(Notice.Recovered(load)))

        assertEquals(NotificationTarget(home.id, disk), problem(disk).target())
        assertEquals(NotificationTarget(home.id, ObjectRef("db01")), problem(ObjectRef("db01")).target())
        assertEquals(
            "a recovery opens the thing that recovered",
            NotificationTarget(home.id, load),
            problem(load).target(),
        )
        assertEquals(NotificationTarget(home.id), shown(home, AndroidNotifier.ID_SUMMARY).target())
        assertEquals(NotificationTarget(home.id), shown(home, AndroidNotifier.ID_OUTAGE).target())
        assertEquals(NotificationTarget(home.id), shown(home, AndroidNotifier.ID_GROUP).target())
    }

    @Test
    fun `a tap can only start this app, the intent cannot be altered, and the notification then goes`() {
        raise(disk, ServiceState.CRITICAL)

        val shown = problem(disk)
        val pending = shadowOf(shown.contentIntent)
        assertEquals(
            "by the name other apps cannot use",
            NotificationTarget.DOOR,
            pending.savedIntent.component?.className,
        )
        assertEquals(context.packageName, pending.savedIntent.component?.packageName)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shown.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    // ---------------------------------------------------------------- loud alerts

    private val alert = FakeLoudAlert()
    private val loud = home.copy(notifications = NotificationSettings(enabled = true, loudAlerts = true))

    private fun loudNotifier() = AndroidNotifier(context, { allowed }, loudAlert = alert).also { notifier = it }

    @Test
    fun `a new critical on a profile with loud alerts sounds one, and its notification is silent`() {
        loudNotifier()

        raise(disk, ServiceState.CRITICAL, profile = loud)

        assertEquals(1, alert.sounded)
        assertTrue("the alert is its sound", problem(disk, loud).isSilent())
        assertNotNull("swiping it away stops the alert", problem(disk, loud).deleteIntent)
    }

    @Test
    fun `a host going down is loud too, and getting worse is`() {
        loudNotifier()

        raiseHost("db01", HostState.DOWN, profile = loud)
        raise(disk, ServiceState.CRITICAL, change = Change.WORSE, profile = loud)

        assertEquals(2, alert.sounded)
    }

    @Test
    fun `warnings, unknowns, recoveries, outages and updates are never loud`() {
        loudNotifier()

        raise(load, ServiceState.WARNING, profile = loud)
        raise(ObjectRef("web01", "Odd"), ServiceState.UNKNOWN, profile = loud)
        raise(disk, ServiceState.CRITICAL, change = Change.UPDATED, profile = loud)
        deliver(loud, post = listOf(Notice.Recovered(load)))
        deliver(loud, post = listOf(Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)))

        assertEquals(0, alert.sounded)
        assertFalse("a warning keeps its channel's sound", problem(ObjectRef("web01", "Odd"), loud).isSilent())
    }

    @Test
    fun `many criticals in one poll are one loud alert`() {
        loudNotifier()

        deliver(loud, post = listOf(criticalNotice(disk), criticalNotice(load)))

        assertEquals(1, alert.sounded)
        assertTrue(problem(disk, loud).isSilent() && problem(load, loud).isSilent())
    }

    @Test
    fun `a later summary that stands in for a critical is loud, and the first poll's never is`() {
        loudNotifier()

        deliver(loud, post = listOf(Notice.Summary(9, first = true)))
        deliver(loud, post = listOf(Notice.Summary(6, first = false, critical = false)))
        assertEquals(0, alert.sounded)

        deliver(loud, post = listOf(Notice.Summary(6, first = false, critical = true)))
        assertEquals(1, alert.sounded)
        assertTrue(shown(loud, AndroidNotifier.ID_SUMMARY).isSilent())
    }

    @Test
    fun `with loud alerts off the profile's criticals sound as they always did`() {
        loudNotifier()

        raise(disk, ServiceState.CRITICAL)

        assertEquals(0, alert.sounded)
        assertFalse(problem(disk).isSilent())
        assertNull(problem(disk).deleteIntent)
    }

    @Test
    fun `without the alarm permission a critical makes its channel's sound instead of none`() {
        loudNotifier()
        alert.can = false

        raise(disk, ServiceState.CRITICAL, profile = loud)

        assertEquals(0, alert.sounded)
        assertFalse(problem(disk, loud).isSilent())
    }

    @Test
    fun `with notifications not allowed there is nothing posted and nothing loud`() {
        loudNotifier()
        allowed = false

        raise(disk, ServiceState.CRITICAL, profile = loud)

        assertEquals(0, alert.sounded)
    }

    private fun criticalNotice(ref: ObjectRef): Notice.Raised {
        val service = ServiceStatus(ref.hostName, checkNotNull(ref.description), ServiceState.CRITICAL, CheckStatus())
        return Notice.Raised(
            ref,
            classify(listOf(HostStatus(ref.hostName, HostState.UP, CheckStatus())), listOf(service)),
            Change.NEW,
        )
    }

    private class FakeLoudAlert : LoudAlert {
        var can = true
        var sounded = 0

        override val sounding: StateFlow<Boolean> = MutableStateFlow(false)

        override fun canSound() = can

        override fun sound() {
            sounded++
        }

        override fun stop() = Unit
    }

    // ----------------------------------------------------------------

    private fun deliver(
        profile: Profile,
        post: List<Notice> = emptyList(),
        remove: List<ObjectRef> = emptyList(),
        removeOutage: Boolean = false,
        mayAct: Boolean = true,
    ) = notifier.deliver(profile, Decision(post, remove, removeOutage, AnnouncedState()), mayAct)

    private fun raise(
        ref: ObjectRef,
        state: ServiceState,
        output: String = "plugin output",
        change: Change = Change.NEW,
        profile: Profile = home,
        mayAct: Boolean = true,
    ) {
        val service =
            ServiceStatus(ref.hostName, checkNotNull(ref.description), state, CheckStatus(pluginOutput = output))
        val host = HostStatus(ref.hostName, HostState.UP, CheckStatus())
        val raised = Notice.Raised(ref, classify(listOf(host), listOf(service)), change)
        deliver(profile, post = listOf(raised), mayAct = mayAct)
    }

    private fun raiseHost(name: String, state: HostState, profile: Profile = home) {
        val host = HostStatus(name, state, CheckStatus(pluginOutput = "no reply"))
        deliver(profile, post = listOf(Notice.Raised(ObjectRef(name), classify(listOf(host), emptyList()), Change.NEW)))
    }

    private fun classify(hosts: List<HostStatus>, services: List<ServiceStatus>): Problem =
        ProblemClassifier.classify(StatusSnapshot(hosts, services, T0)).unhandled.single()

    private fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun problems(): List<StatusBarNotification> = active().filter { it.id == AndroidNotifier.ID_PROBLEM }

    private fun problem(ref: ObjectRef, profile: Profile = home): Notification =
        problems().single { it.tag == AndroidNotifier.problemTag(profile.id, ref) }.notification

    private fun shown(profile: Profile, id: Int): Notification =
        active().single { it.id == id && it.tag == AndroidNotifier.profileTag(profile.id) }.notification

    private fun Notification.hasLargeIcon(): Boolean = getLargeIcon() != null

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** How a member of a group is made silent on Android 8 and later: it leaves the sound to the group's entry. */
    private fun Notification.isSilent(): Boolean = groupAlertBehavior == NotificationCompat.GROUP_ALERT_SUMMARY

    /** And the entry leaves it to the members, so between them nothing sounds. */
    private fun Notification.isSilentGroupEntry(): Boolean =
        groupAlertBehavior == NotificationCompat.GROUP_ALERT_CHILDREN

    private fun Notification.target(): NotificationTarget? =
        NotificationTarget.from(context, shadowOf(contentIntent).savedIntent)

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
