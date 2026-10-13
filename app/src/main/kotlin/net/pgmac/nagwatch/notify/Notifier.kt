// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.firstOutputLine
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import net.pgmac.nagwatch.ui.theme.StateColors

/** Puts a poll's [Decision] in front of the person. What to say was decided already; this is only how. */
interface Notifier {
    fun deliver(profile: Profile, decision: Decision)

    /**
     * Takes down everything this profile has showing: its problems, its summary, its outage and
     * its group's entry. For a profile that is no longer watched in the background, so that
     * nothing is left that nothing will ever update or remove.
     */
    fun clear(profileId: Long)

    /**
     * Takes down everything Nagwatch has showing for a profile that is not in [keep]. For app
     * start, to catch what was left behind (an app killed between saving a profile and clearing,
     * or notifications stranded by a version before this existed).
     */
    fun sweep(keep: Set<Long>)
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
        /**
         * Makes every channel, so Android's settings list all of them. Creating a channel that
         * exists changes nothing the person has set, so this is safe to repeat, and it is what
         * the app does before it sends the person there: until something has been posted there
         * would be nothing to show.
         */
        fun createAll(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            entries.forEach { channel ->
                manager.createNotificationChannel(
                    NotificationChannel(channel.id, context.getString(channel.label), channel.importance),
                )
            }
        }

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
class AndroidNotifier internal constructor(
    private val context: Context,
    private val permission: NotificationPermission,
    /** What Android says is showing. It lags: see [tidyGroup]. A parameter so a test can make it lag. */
    private val showing: (NotificationManager) -> List<StatusBarNotification>,
) : Notifier {
    @Inject
    constructor(@ApplicationContext context: Context, permission: NotificationPermission) :
        this(context, permission, { it.activeNotifications.toList() })

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun deliver(profile: Profile, decision: Decision) {
        // Taking down needs no permission, and is done even when posting is not allowed.
        val takenDown = buildSet {
            decision.remove.forEach { add(problemTag(profile.id, it) to ID_PROBLEM) }
            if (decision.removeOutage) add(profileTag(profile.id) to ID_OUTAGE)
        }
        takenDown.forEach { (tag, id) -> manager.cancel(tag, id) }

        val posting = decision.post.isNotEmpty() && permission.isHeld()
        if (posting) {
            ensureChannels()
            decision.post.forEach { post(profile, it) }
        }
        tidyGroup(profile, takenDown, posting)
    }

    override fun clear(profileId: Long) {
        // By identity first: these three are known without looking, so a list that has not
        // caught up with an earlier cancel or post cannot leave them behind.
        manager.cancel(profileTag(profileId), ID_SUMMARY)
        manager.cancel(profileTag(profileId), ID_OUTAGE)
        manager.cancel(profileTag(profileId), ID_GROUP)
        val key = group(profileId)
        showing(manager).filter { it.notification.group == key }.forEach { manager.cancel(it.tag, it.id) }
    }

    override fun sweep(keep: Set<Long>) {
        showing(manager).forEach { shown ->
            val owner = profileOf(shown.notification.group)
            // One that cannot be attributed to a profile is left alone: it is not ours to judge.
            if (owner != null && owner !in keep) manager.cancel(shown.tag, shown.id)
        }
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
            .setColor(stateColors().of(notice.problem.severity))
            .setContentTitle(title)
            .text(notice.problem.check.firstOutputLine())
            // An update says nothing new enough to interrupt for.
            .setSilent(notice.change == Notice.Change.UPDATED)
            .build()
    }

    private fun recovered(profile: Profile, ref: ObjectRef): Notification {
        val title = if (ref.description == null) R.string.notify_host_recovered else R.string.notify_service_recovered
        return builder(profile, NotifyChannel.RECOVERED, NotificationTarget(profile.id, ref))
            .setSmallIcon(R.drawable.ic_check)
            .setColor(stateColors().ok.toArgb())
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
     *
     * Posting and cancelling are requests, and what Android reports as showing does not reflect
     * them at once. So what was just taken down is not counted as a member even if Android still
     * lists it, and having just posted counts as having members even if Android does not yet.
     * Without this the entry outlived its last member until the next poll (found on a phone).
     */
    private fun tidyGroup(profile: Profile, takenDown: Set<Pair<String, Int>>, posted: Boolean) {
        val key = group(profile.id)
        val others = showing(manager).count { shown ->
            shown.notification.group == key &&
                shown.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                (shown.tag to shown.id) !in takenDown
        }
        if (!posted && others == 0) {
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

    private fun ensureChannels() = NotifyChannel.createAll(context)

    /**
     * The state colours the app's own screens use, in the variant for the phone's theme right now.
     * They tint the small icon in the shade. The status bar draws it white whatever is set, which is
     * Android's rule, and the title says the state in words, so the colour is only a second signal.
     * A summary, the group's entry and an outage are left untinted: no state colour is true of them.
     */
    private fun stateColors(): StateColors {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DarkStateColors else LightStateColors
    }

    private fun StateColors.of(severity: Severity): Int = when (severity) {
        Severity.HOST_DOWN, Severity.HOST_UNREACHABLE, Severity.CRITICAL -> critical
        Severity.WARNING -> warning
        Severity.UNKNOWN -> unknown
    }.toArgb()

    private fun ObjectRef.describe(): String =
        description?.let { context.getString(R.string.notify_service_on_host, it, hostName) } ?: hostName

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

        /** A line break cannot be part of a Nagios name, so two different objects never share a tag. */
        fun problemTag(profileId: Long, ref: ObjectRef): String =
            "$profileId\n${ref.hostName}\n${ref.description.orEmpty()}"

        fun profileTag(profileId: Long): String = profileId.toString()

        private const val GROUP_PREFIX = "profile-"

        fun group(profileId: Long): String = "$GROUP_PREFIX$profileId"

        /** The profile a group key belongs to, or null for a key that is not made by [group]. */
        fun profileOf(group: String?): Long? =
            group?.takeIf { it.startsWith(GROUP_PREFIX) }?.removePrefix(GROUP_PREFIX)?.toLongOrNull()
    }
}
