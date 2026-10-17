// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ActionProgress

/**
 * Notifications as Android has them.
 *
 * Each problem has one notification, found again by a tag made from the profile and the
 * object, so that getting worse, getting better and recovering all replace it and never
 * stack. A profile's notifications share a group.
 *
 * Nothing here decides *whether* to say something: that is [Announcer], and has tests of its own.
 * Nor what a notification looks like: that is [NotificationFactory].
 */
@Singleton
class AndroidNotifier internal constructor(
    private val context: Context,
    private val permission: NotificationPermission,
    /** What Android says is showing. It lags: see [tidyGroup]. A parameter so a test can make it lag. */
    private val showing: (NotificationManager) -> List<StatusBarNotification> = { it.activeNotifications.toList() },
    /** The set the person chose, read each time something is posted so that a change shows on the next one. */
    emojiSet: () -> EmojiSet = { EmojiSet.DEFAULT },
    /** Whether to give a notification a large icon, read each time something is posted. */
    largeIcon: () -> Boolean = { true },
    private val loudAlert: LoudAlert = LoudAlert.NONE,
) : Notifier {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        permission: NotificationPermission,
        settings: NotifierSettings,
        loudAlert: LoudAlert,
    ) : this(
        context,
        permission,
        { it.activeNotifications.toList() },
        // Posting runs on a worker, off the main thread, and a DataStore read is a small file.
        { runBlocking { settings.emojiSet.first() } },
        { runBlocking { settings.largeIcon.first() } },
        loudAlert,
    )

    private val build = NotificationFactory(context, emojiSet, largeIcon)

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
            // Asked once per poll: the notifications go silent only if the alert can be heard instead.
            val loud = profile.notifications.loudAlerts && decision.post.any { it.isLoud() } && loudAlert.canSound()
            decision.post.forEach { post(profile, it, mayAct, loud && it.isLoud()) }
            // One for the whole poll, however many it raised.
            if (loud) loudAlert.sound()
        }
        tidyGroup(profile.id, profile.name, takenDown, posting)
    }

    override fun acting(request: ActionRequest) {
        val words = when (request.action) {
            NoticeAction.ACKNOWLEDGE -> R.string.notify_acting_acknowledge
            NoticeAction.RECHECK -> R.string.notify_acting_recheck
        }
        // No buttons while one is on its way: a second press would be a second command.
        redraw(request) { with(build) { it.text(context.getString(words)).setProgress(0, 0, true).setOngoing(true) } }
    }

    override fun acted(request: ActionRequest, progress: ActionProgress) {
        // Heard when it did not work: that is news, and on a watch a silent change is not seen at all.
        redraw(request, silent = progress.worked) { builder ->
            with(build) {
                builder.text(progress.sentence(context))
                if (progress.leavesTheProblem) {
                    // Still a problem, and still theirs to deal with: the buttons come back.
                    builder.withActions(request)
                } else {
                    // Dealt with. The next poll takes this down anyway; this is so it does not wait for one.
                    builder.setTimeoutAfter(ACTED_SHOWN_FOR_MS)
                }
            }
        }
    }

    override fun needsUnlock(request: ActionRequest) {
        redraw(request, silent = false) {
            with(build) { it.text(context.getString(R.string.notify_needs_unlock)).withActions(request) }
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
        manager.notify(problemTag(request.profileId, request.ref), ID_PROBLEM, build.redrawn(request, silent, change))
        // It may have been swiped away meanwhile, and its group's entry with it.
        tidyGroup(request.profileId, request.profileName, emptySet(), posted = true)
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

    private fun post(profile: Profile, notice: Notice, mayAct: Boolean, loud: Boolean) {
        when (notice) {
            is Notice.Raised ->
                manager.notify(
                    problemTag(profile.id, notice.ref),
                    ID_PROBLEM,
                    build.raised(profile, notice, mayAct, loud),
                )

            is Notice.Recovered ->
                manager.notify(problemTag(profile.id, notice.ref), ID_PROBLEM, build.recovered(profile, notice.ref))

            is Notice.Summary -> manager.notify(
                profileTag(profile.id),
                ID_SUMMARY,
                build.summary(profile, notice, loud),
            )

            is Notice.Outage -> manager.notify(profileTag(profile.id), ID_OUTAGE, build.outage(profile, notice))
        }
    }

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
            manager.notify(profileTag(profileId), ID_GROUP, build.groupEntry(profileId, profileName))
        }
    }

    private fun ensureChannels() = NotifyChannel.createAll(context)

    /** Down or critical, new or worse, or a later summary that stands in for one (docs/adr/0013). */
    private fun Notice.isLoud(): Boolean = when (this) {
        is Notice.Raised ->
            change != Notice.Change.UPDATED &&
                NotifyChannel.of(problem.severity) == NotifyChannel.CRITICAL

        is Notice.Summary -> critical && !first

        is Notice.Recovered, is Notice.Outage -> false
    }

    companion object {
        const val ID_PROBLEM = 1
        const val ID_OUTAGE = 2
        const val ID_SUMMARY = 3
        const val ID_GROUP = 4

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
