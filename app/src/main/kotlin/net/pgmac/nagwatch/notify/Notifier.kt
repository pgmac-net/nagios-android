// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.profile.Profile

/** Puts a poll's [Decision] in front of the person. What to say was decided already; this is only how. */
fun interface Notifier {
    fun deliver(profile: Profile, decision: Decision)
}

/**
 * The kinds of notification, as Android's settings show them. The person can silence or block
 * each one there, which is why they are split by what they would want to treat differently.
 *
 * An app sets a channel's importance once, when it creates it. After that it is the person's.
 */
enum class NotifyChannel(val id: String, val label: Int, val importance: Int) {
    CRITICAL("problems_critical", R.string.channel_critical, NotificationManager.IMPORTANCE_HIGH),
    WARNING("problems_warning", R.string.channel_warning, NotificationManager.IMPORTANCE_DEFAULT),
    UNKNOWN("problems_unknown", R.string.channel_unknown, NotificationManager.IMPORTANCE_DEFAULT),

    /** Low importance makes no sound: good news should not wake anyone. */
    RECOVERED("recovered", R.string.channel_recovered, NotificationManager.IMPORTANCE_LOW),
    OUTAGE("unreachable", R.string.channel_outage, NotificationManager.IMPORTANCE_DEFAULT),
    SUMMARY("summaries", R.string.channel_summary, NotificationManager.IMPORTANCE_DEFAULT),
    ;

    companion object {
        fun of(severity: Severity): NotifyChannel = when (severity) {
            Severity.HOST_DOWN, Severity.HOST_UNREACHABLE, Severity.CRITICAL -> CRITICAL
            Severity.WARNING -> WARNING
            Severity.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * Notifications as Android has them.
 *
 * Each problem has one notification, found again by a tag made from the profile and the
 * object, so that getting worse, getting better and recovering all replace it and never
 * stack. A profile's notifications share a group.
 *
 * Nothing here decides *whether* to say something: that is [Announcer], and has tests of its own.
 */
@Singleton
class AndroidNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permission: NotificationPermission,
) : Notifier {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun deliver(profile: Profile, decision: Decision) {
        // Taking down needs no permission, and is done even when posting is not allowed.
        decision.remove.forEach { manager.cancel(problemTag(profile.id, it), ID_PROBLEM) }
        if (decision.removeOutage) manager.cancel(profileTag(profile.id), ID_OUTAGE)

        if (decision.post.isNotEmpty() && permission.isHeld()) {
            ensureChannels()
            decision.post.forEach { post(profile, it) }
        }
        tidyGroup(profile)
    }

    private fun post(profile: Profile, notice: Notice) {
        when (notice) {
            is Notice.Raised ->
                manager.notify(problemTag(profile.id, notice.ref), ID_PROBLEM, raised(profile, notice))

            is Notice.Recovered ->
                manager.notify(problemTag(profile.id, notice.ref), ID_PROBLEM, recovered(profile, notice.ref))

            is Notice.Summary -> manager.notify(profileTag(profile.id), ID_SUMMARY, summary(profile, notice))

            is Notice.Outage -> manager.notify(profileTag(profile.id), ID_OUTAGE, outage(profile, notice))
        }
    }

    private fun raised(profile: Profile, notice: Notice.Raised): Notification {
        val what = context.getString(notice.problem.severity.word(), notice.ref.describe())
        val title = if (notice.change == Notice.Change.WORSE) context.getString(R.string.notify_now, what) else what
        return builder(profile, NotifyChannel.of(notice.problem.severity), NotificationTarget(profile.id, notice.ref))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setContentTitle(title)
            .text(notice.problem.firstLine())
            // An update says nothing new enough to interrupt for.
            .setSilent(notice.change == Notice.Change.UPDATED)
            .build()
    }

    private fun recovered(profile: Profile, ref: ObjectRef): Notification {
        val title = if (ref.description == null) R.string.notify_host_recovered else R.string.notify_service_recovered
        return builder(profile, NotifyChannel.RECOVERED, NotificationTarget(profile.id, ref))
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(context.getString(title, ref.describe()))
            .setSilent(true)
            // Good news goes stale: it clears itself, where a problem stays until dealt with.
            .setTimeoutAfter(RECOVERY_SHOWN_FOR_MS)
            .build()
    }

    private fun summary(profile: Profile, notice: Notice.Summary): Notification {
        val title = if (notice.first) R.plurals.notify_summary_first else R.plurals.notify_summary_more
        return builder(profile, NotifyChannel.SUMMARY, NotificationTarget(profile.id))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setContentTitle(context.resources.getQuantityString(title, notice.count, notice.count, profile.name))
            .text(context.getString(R.string.notify_summary_text))
            .build()
    }

    private fun outage(profile: Profile, notice: Notice.Outage): Notification =
        builder(profile, NotifyChannel.OUTAGE, NotificationTarget(profile.id))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setContentTitle(context.getString(R.string.notify_outage_title, profile.name))
            .text(context.getString(notice.cause.sentence()))
            .setSilent(notice.quiet)
            .build()

    /** What every notification of a profile has: where it goes, which profile, and its group. */
    private fun builder(profile: Profile, channel: NotifyChannel, target: NotificationTarget) =
        NotificationCompat.Builder(context, channel.id)
            .setSubText(profile.name)
            .setContentIntent(open(target))
            .setAutoCancel(true)
            .setGroup(group(profile.id))
            // The group's own entry never makes a sound; its members do.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)

    private fun NotificationCompat.Builder.text(text: String): NotificationCompat.Builder =
        if (text.isEmpty()) this else setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))

    private fun open(target: NotificationTarget): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        target.toIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Android shows a group only if it has an entry of its own, and does not remove that entry
     * when the last member goes. So it is posted while there are members and taken away after.
     */
    private fun tidyGroup(profile: Profile) {
        val key = group(profile.id)
        val members = manager.activeNotifications.count { shown ->
            shown.notification.group == key && shown.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        if (members == 0) {
            manager.cancel(profileTag(profile.id), ID_GROUP)
        } else if (permission.isHeld()) {
            ensureChannels()
            val entry = NotificationCompat.Builder(context, NotifyChannel.SUMMARY.id)
                .setSmallIcon(R.drawable.ic_tab_problems)
                .setContentTitle(profile.name)
                .setContentIntent(open(NotificationTarget(profile.id)))
                .setGroup(key)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .build()
            manager.notify(profileTag(profile.id), ID_GROUP, entry)
        }
    }

    /** Creating a channel that exists changes nothing the person has set, so this is safe to repeat. */
    private fun ensureChannels() {
        NotifyChannel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(channel.id, context.getString(channel.label), channel.importance),
            )
        }
    }

    private fun ObjectRef.describe(): String =
        description?.let { context.getString(R.string.notify_service_on_host, it, hostName) } ?: hostName

    /** Plugin output is whatever a script printed. One line of it, and not an unbounded one. */
    private fun Problem.firstLine(): String =
        check.pluginOutput.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TEXT)

    private fun Severity.word(): Int = when (this) {
        Severity.HOST_DOWN -> R.string.notify_down
        Severity.HOST_UNREACHABLE -> R.string.notify_unreachable_host
        Severity.CRITICAL -> R.string.notify_critical
        Severity.WARNING -> R.string.notify_warning
        Severity.UNKNOWN -> R.string.notify_unknown
    }

    private fun OutageCause.sentence(): Int = when (this) {
        OutageCause.UNREACHABLE -> R.string.notify_outage_unreachable
        OutageCause.CERTIFICATE -> R.string.notify_outage_certificate
        OutageCause.WRONG_ANSWER -> R.string.notify_outage_wrong_answer
        OutageCause.SERVER_ERROR -> R.string.notify_outage_server_error
        OutageCause.LOCAL_NETWORK -> R.string.notify_outage_local_network
        OutageCause.CREDENTIALS_REJECTED -> R.string.notify_outage_credentials_rejected
        OutageCause.ACCESS_REJECTED -> R.string.notify_outage_access_rejected
        OutageCause.NOT_ALLOWED -> R.string.notify_outage_not_allowed
        OutageCause.CREDENTIALS_UNREADABLE -> R.string.notify_outage_credentials_unreadable
        OutageCause.MISCONFIGURED -> R.string.notify_outage_misconfigured
    }

    companion object {
        const val ID_PROBLEM = 1
        const val ID_OUTAGE = 2
        const val ID_SUMMARY = 3
        const val ID_GROUP = 4

        val RECOVERY_SHOWN_FOR_MS: Long = TimeUnit.HOURS.toMillis(1)
        private const val MAX_TEXT = 300

        /** A line break cannot be part of a Nagios name, so two different objects never share a tag. */
        fun problemTag(profileId: Long, ref: ObjectRef): String =
            "$profileId\n${ref.hostName}\n${ref.description.orEmpty()}"

        fun profileTag(profileId: Long): String = profileId.toString()

        fun group(profileId: Long): String = "profile-$profileId"
    }
}
