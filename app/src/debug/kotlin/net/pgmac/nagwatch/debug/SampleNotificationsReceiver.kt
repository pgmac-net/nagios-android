// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.notify.AndroidNotificationPermission
import net.pgmac.nagwatch.notify.AndroidNotifier
import net.pgmac.nagwatch.notify.AnnouncedState
import net.pgmac.nagwatch.notify.Decision
import net.pgmac.nagwatch.notify.Notice
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.notify.ref
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.StatusError

/**
 * Posts one notification of every kind the app can send, to see them. Debug builds only; a
 * release build has no such class.
 *
 * What a notification looks like (the tint, the text, the group) cannot be seen without one,
 * and one needs a Nagios with something new wrong on it. This goes through the same
 * `AndroidNotifier` a poll does, with made-up problems and a profile of its own, so it needs
 * no Nagios and touches nothing of the person's:
 *
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.SampleNotificationsReceiver
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.SampleNotificationsReceiver --es action clear
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.SampleNotificationsReceiver --es only critical
 *     adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.SampleNotificationsReceiver --es set faces
 *
 * To try a button against a real Nagios, name a real profile and one of its services. **Its
 * Acknowledge button is then real too**: it will acknowledge that service for everyone.
 *
 *     ... SampleNotificationsReceiver --el profile ID --es host HOST --es service SERVICE
 *     ... SampleNotificationsReceiver --el profile ID --es action clear
 *
 * Only adb's shell and the system can send it (the manifest asks for the DUMP permission). The
 * app takes these down by itself the next time it starts, as it does for any profile it does not
 * know; a tap on one opens the app and finds no such profile.
 */
class SampleNotificationsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The set to draw them with, by id (none, circles, signs, squares, faces): not the person's own setting.
        val set = net.pgmac.nagwatch.notify.EmojiSet.of(intent.getStringExtra(EXTRA_SET))
        val notifier = AndroidNotifier(
            context,
            AndroidNotificationPermission(context),
            emojiSet = { set },
            largeIcon = { intent.getBooleanExtra(EXTRA_LARGE, true) },
        )
        // A real profile's number, to try a button all the way to a Nagios. Without it, a profile of
        // the samples' own that does not exist, so a button's press sends nothing.
        val profile = PROFILE.copy(id = intent.getLongExtra(EXTRA_PROFILE, PROFILE.id))
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_CLEAR) {
            notifier.clear(profile.id)
            return
        }
        val host = intent.getStringExtra(EXTRA_HOST)
        val service = intent.getStringExtra(EXTRA_SERVICE)
        val only = intent.getStringExtra(EXTRA_ONLY)
        val samples = when {
            host != null && service != null -> listOf(real(host, service))
            else -> samples().filter { only == null || it.kind() == only }
        }
        notifier.deliver(profile, Decision(post = samples, state = AnnouncedState()))
    }

    /** A made-up warning about a service that does exist, so that its buttons have something real to act on. */
    private fun real(host: String, service: String): Notice {
        val up = HostStatus(host, HostState.UP, CheckStatus())
        val status =
            ServiceStatus(host, service, ServiceState.WARNING, CheckStatus(pluginOutput = "sample for a real service"))
        val problem = ProblemClassifier.classify(
            StatusSnapshot(listOf(up), listOf(status), Instant.now()),
        ).unhandled.single()
        return Notice.Raised(problem.ref, problem, Notice.Change.NEW)
    }

    private fun Notice.kind(): String = when (this) {
        is Notice.Raised -> when (problem.severity) {
            Severity.HOST_DOWN, Severity.HOST_UNREACHABLE -> "down"
            Severity.CRITICAL -> "critical"
            Severity.WARNING -> "warning"
            Severity.UNKNOWN -> "unknown"
        }

        is Notice.Recovered -> "recovered"

        else -> "other"
    }

    private fun samples(): List<Notice> {
        val up = HostStatus("web01", HostState.UP, CheckStatus())
        val services = listOf(
            sample("Disk /", ServiceState.CRITICAL, "DISK CRITICAL - 3% free"),
            sample("Load", ServiceState.WARNING, "WARNING - load 9.1"),
            sample("Odd", ServiceState.UNKNOWN, "UNKNOWN - no data"),
        )
        val down = HostStatus("db01", HostState.DOWN, CheckStatus(pluginOutput = "no reply"))
        val report = ProblemClassifier.classify(StatusSnapshot(listOf(up, down), services, Instant.now()))
        val raised = report.unhandled.map { Notice.Raised(it.ref, it, Notice.Change.NEW) }
        return raised + Notice.Recovered(ObjectRef("web02", "Ping")) +
            Notice.Outage(OutageCause.UNREACHABLE, StatusError.InvalidUrl, quiet = false)
    }

    private fun sample(description: String, state: ServiceState, output: String) =
        ServiceStatus("web01", description, state, CheckStatus(pluginOutput = output))

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_SET = "set"

        /** `--ez large false` draws the samples without the large icon. */
        const val EXTRA_LARGE = "large"

        /** One of down, critical, warning, unknown or recovered: post only that one. */
        const val EXTRA_ONLY = "only"

        /** With [EXTRA_HOST] and [EXTRA_SERVICE]: post one sample for that service of that profile. */
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVICE = "service"
        const val ACTION_CLEAR = "clear"

        /** Not a number a real profile can have, so nothing of the person's is ever replaced. */
        private val PROFILE = Profile(
            id = -7001,
            name = "Sample (debug)",
            baseUrl = "https://nagios.invalid/nagios",
            username = "sample",
            hasPassword = false,
            accessClientId = "",
            hasAccessClientSecret = false,
            customHeaderNames = emptyList(),
            allowCleartext = false,
        )
    }
}
