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
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.nagios.model.firstOutputLine
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import net.pgmac.nagwatch.ui.theme.StateColors

/** Puts a poll's [Decision] in front of the person. What to say was decided already; this is only how. */
interface Notifier {
    /**
     * @param mayAct false for a profile whose user Nagios takes no commands from: a problem's
     *   notification then has no buttons, since neither could work.
     */
    fun deliver(profile: Profile, decision: Decision, mayAct: Boolean = true)

    /** A button on a problem's notification was pressed: say on the notification that it is on its way. */
    fun acting(request: ActionRequest)

    /** What became of it, said in the same place, because there is no screen to say it on. */
    fun acted(request: ActionRequest, progress: ActionProgress)

    /** A button was pressed on a locked phone: nothing was sent, and the notification says to unlock. */
    fun needsUnlock(request: ActionRequest)

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

    override fun deliver(profile: Profile, decision: Decision, mayAct: Boolean) {
        // Taking down needs no permission, and is done even when posting is not allowed.
        val takenDown = buildSet {
            decision.remove.forEach { add(problemTag(profile.id, it) to ID_PROBLEM) }
            if (decision.removeOutage) add(profileTag(profile.id) to ID_OUTAGE)
        }
        takenDown.forEach { (tag, id) -> manager.cancel(tag, id) }

        val posting = decision.post.isNotEmpty() && permission.isHeld()
        if (posting) {
            ensureChannels()
            decision.post.forEach { post(profile, it, mayAct) }
        }
        tidyGroup(profile.id, profile.name, takenDown, posting)
    }

    override fun acting(request: ActionRequest) {
        val words = when (request.action) {
            NoticeAction.ACKNOWLEDGE -> R.string.notify_acting_acknowledge
            NoticeAction.RECHECK -> R.string.notify_acting_recheck
        }
        // No buttons while one is on its way: a second press would be a second command.
        redraw(request) { it.text(context.getString(words)).setProgress(0, 0, true).setOngoing(true) }
    }

    override fun acted(request: ActionRequest, progress: ActionProgress) {
        // Heard when it did not work: that is news, and on a watch a silent change is not seen at all.
        redraw(request, silent = progress.worked) { builder ->
            builder.text(progress.sentence())
            if (progress.leavesTheProblem) {
                // Still a problem, and still theirs to deal with: the buttons come back.
                builder.withActions(request)
            } else {
                // Dealt with. The next poll takes this down anyway; this is so it does not wait for one.
                builder.setTimeoutAfter(ACTED_SHOWN_FOR_MS)
            }
        }
    }

    override fun needsUnlock(request: ActionRequest) {
        redraw(request, silent = false) {
            it.text(context.getString(R.string.notify_needs_unlock)).withActions(request)
        }
    }

    /**
     * Draws a problem's notification again from what its button carried, in its own place.
     *
     * @param silent true while a command is on its way and when it worked: the person pressed the
     *   button and does not need calling over to be told so. False for a refusal or a failure,
     *   which they do need to hear of; found with a watch, which showed a silent refusal as nothing
     *   having happened (pgmac-net/nagios-android#76).
     */
    private fun redraw(request: ActionRequest, silent: Boolean = true, change: (NotificationCompat.Builder) -> Unit) {
        if (!permission.isHeld()) return
        ensureChannels()
        val notification = builder(
            request.profileId,
            request.profileName,
            request.channelId,
            NotificationTarget(request.profileId, request.ref),
        )
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setColor(request.color)
            .setContentTitle(request.title)
            .setSilent(silent)
            .also(change)
            .build()
        manager.notify(problemTag(request.profileId, request.ref), ID_PROBLEM, notification)
        // It may have been swiped away meanwhile, and its group's entry with it.
        tidyGroup(request.profileId, request.profileName, emptySet(), posted = true)
    }

    private fun NotificationCompat.Builder.withActions(request: ActionRequest): NotificationCompat.Builder {
        NoticeAction.entries.forEach { action ->
            val label = when (action) {
                NoticeAction.ACKNOWLEDGE -> R.string.action_acknowledge
                NoticeAction.RECHECK -> R.string.action_recheck
            }
            val pressed = request.copy(action = action).toPendingIntent(context)
            addAction(
                NotificationCompat.Action.Builder(0, context.getString(label), pressed)
                    // A notification is shown on the lock screen, and its buttons with it. These
                    // change a Nagios for everyone who uses it, with this profile's credentials, so
                    // whoever is holding a locked phone must not be able to press them. Android
                    // asks for the unlock first (from Android 12; the receiver checks on every version).
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        return this
    }

    /** What became of a button's command, in a line. The screens say the same things at more length. */
    private fun ActionProgress.sentence(): String = when (this) {
        is ActionProgress.Confirmed -> when (command) {
            is Command.Acknowledge -> context.getString(R.string.progress_confirmed_acknowledge)
            else -> context.getString(R.string.notify_acted_recheck)
        }

        is ActionProgress.NotConfirmed -> context.getString(R.string.notify_acted_not_confirmed)

        is ActionProgress.Refused -> reason.sentence(detail)

        is ActionProgress.NotSent -> context.getString(R.string.notify_acted_not_sent)

        else -> context.getString(R.string.progress_unknown)
    }

    /** Nagios' own words are used only where they say something the app's do not. */
    private fun Refusal.sentence(detail: String): String = when (this) {
        Refusal.READ_ONLY -> context.getString(R.string.refused_read_only)

        Refusal.NOT_AUTHORISED -> context.getString(R.string.refused_not_authorised)

        Refusal.COMMANDS_DISABLED -> context.getString(R.string.refused_commands_disabled)

        Refusal.COULD_NOT_WRITE -> context.getString(R.string.refused_could_not_write)

        Refusal.AUTHENTICATION_DISABLED -> context.getString(R.string.refused_authentication_disabled)

        Refusal.INVALID -> when {
            detail.isBlank() -> context.getString(R.string.refused_invalid_no_detail)
            else -> context.getString(R.string.refused_invalid, detail)
        }
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

    private fun post(profile: Profile, notice: Notice, mayAct: Boolean) {
        when (notice) {
            is Notice.Raised ->
                manager.notify(problemTag(profile.id, notice.ref), ID_PROBLEM, raised(profile, notice, mayAct))

            is Notice.Recovered ->
                manager.notify(problemTag(profile.id, notice.ref), ID_PROBLEM, recovered(profile, notice.ref))

            is Notice.Summary -> manager.notify(profileTag(profile.id), ID_SUMMARY, summary(profile, notice))

            is Notice.Outage -> manager.notify(profileTag(profile.id), ID_OUTAGE, outage(profile, notice))
        }
    }

    private fun raised(profile: Profile, notice: Notice.Raised, mayAct: Boolean): Notification {
        val what = context.getString(notice.problem.severity.word(), notice.ref.describe())
        val words = if (notice.change == Notice.Change.WORSE) context.getString(R.string.notify_now, what) else what
        val title = "${notice.problem.severity.emoji()} $words"
        val channel = NotifyChannel.of(notice.problem.severity)
        val color = stateColors().of(notice.problem.severity)
        val text = notice.problem.check.firstOutputLine()
        val drawn = builder(profile, channel, NotificationTarget(profile.id, notice.ref))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setColor(color)
            .setContentTitle(title)
            .text(text)
            // An update says nothing new enough to interrupt for.
            .setSilent(notice.change == Notice.Change.UPDATED)
        if (mayAct) {
            // The button carries what the notification says, so the answer can be drawn in its place.
            val request = ActionRequest(
                profileId = profile.id,
                profileName = profile.name,
                ref = notice.ref,
                action = NoticeAction.ACKNOWLEDGE,
                channelId = channel.id,
                title = title,
                text = text,
                color = color,
            )
            drawn.withActions(request)
        }
        return drawn.build()
    }

    private fun recovered(profile: Profile, ref: ObjectRef): Notification {
        val title = if (ref.description == null) R.string.notify_host_recovered else R.string.notify_service_recovered
        return builder(profile, NotifyChannel.RECOVERED, NotificationTarget(profile.id, ref))
            .setSmallIcon(R.drawable.ic_check)
            .setColor(stateColors().ok.toArgb())
            .setContentTitle("$EMOJI_RECOVERED ${context.getString(title, ref.describe())}")
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
            .setContentTitle("$EMOJI_OUTAGE ${context.getString(R.string.notify_outage_title, profile.name)}")
            .text(context.getString(notice.cause.sentence()))
            .setSilent(notice.quiet)
            .build()

    /** What every notification of a profile has: where it goes, which profile, and its group. */
    private fun builder(profile: Profile, channel: NotifyChannel, target: NotificationTarget) =
        builder(profile.id, profile.name, channel.id, target)

    private fun builder(profileId: Long, profileName: String, channelId: String, target: NotificationTarget) =
        NotificationCompat.Builder(context, channelId)
            .setSubText(profileName)
            .setContentIntent(open(target))
            .setAutoCancel(true)
            .setGroup(group(profileId))
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
    private fun tidyGroup(profileId: Long, profileName: String, takenDown: Set<Pair<String, Int>>, posted: Boolean) {
        val key = group(profileId)
        val others = showing(manager).count { shown ->
            shown.notification.group == key &&
                shown.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                (shown.tag to shown.id) !in takenDown
        }
        if (!posted && others == 0) {
            manager.cancel(profileTag(profileId), ID_GROUP)
        } else if (permission.isHeld()) {
            ensureChannels()
            val entry = NotificationCompat.Builder(context, NotifyChannel.SUMMARY.id)
                .setSmallIcon(R.drawable.ic_tab_problems)
                .setContentTitle(profileName)
                .setContentIntent(open(NotificationTarget(profileId)))
                .setGroup(key)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .build()
            manager.notify(profileTag(profileId), ID_GROUP, entry)
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

    /**
     * A second signal in the title, for a shade that will not show the tint (pgmac-net/nagios-android#81).
     * Code and not strings: the same in every language, and a translation must not be able to drop it.
     * A summary and the group's entry have none, as they have no tint: no state is true of them.
     */
    private fun Severity.emoji(): String = when (this) {
        Severity.HOST_DOWN, Severity.HOST_UNREACHABLE, Severity.CRITICAL -> EMOJI_CRITICAL
        Severity.WARNING -> EMOJI_WARNING
        Severity.UNKNOWN -> EMOJI_UNKNOWN
    }

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

        // Circles in the colours of StateColors: red, amber, orange, green.
        const val EMOJI_CRITICAL = "\uD83D\uDD34"
        const val EMOJI_WARNING = "\uD83D\uDFE1"
        const val EMOJI_UNKNOWN = "\uD83D\uDFE0"
        const val EMOJI_RECOVERED = "\uD83D\uDFE2"
        const val EMOJI_OUTAGE = "\uD83D\uDCE1"

        val RECOVERY_SHOWN_FOR_MS: Long = TimeUnit.HOURS.toMillis(1)

        /** Long enough to read "Acknowledged", and no longer: it is no longer news. */
        val ACTED_SHOWN_FOR_MS: Long = TimeUnit.MINUTES.toMillis(1)

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
