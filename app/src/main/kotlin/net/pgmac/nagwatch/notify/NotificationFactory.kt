// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.firstOutputLine
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.ui.theme.DarkStateColors
import net.pgmac.nagwatch.ui.theme.LightStateColors
import net.pgmac.nagwatch.ui.theme.StateColors
import net.pgmac.nagwatch.ui.theme.of

/**
 * What each kind of notification looks like: title, text, icon, colour, buttons. Posting them,
 * taking them down and keeping a profile's group tidy is [AndroidNotifier]'s.
 */
internal class NotificationFactory(
    private val context: Context,
    /** The set the person chose, read each time something is built so that a change shows on the next one. */
    private val emojiSet: () -> EmojiSet,
    /** Whether to give a notification a large icon, read each time something is built. */
    private val largeIcon: () -> Boolean,
) {
    /**
     * @param loud a loud alert stands in for this one's sound: it is posted silent, and swiping it
     *   away stops the alert.
     */
    fun raised(profile: Profile, notice: Notice.Raised, mayAct: Boolean, loud: Boolean): Notification {
        val what = context.getString(notice.problem.severity.notifyWord(), notice.ref.describe(context))
        val words = if (notice.change == Notice.Change.WORSE) context.getString(R.string.notify_now, what) else what
        val title = emojis().of(notice.problem.severity).before(words)
        val channel = NotifyChannel.of(notice.problem.severity)
        val color = stateColors().of(notice.problem.severity).toArgb()
        val text = notice.problem.check.firstOutputLine()
        val drawn = builder(profile, channel, NotificationTarget(profile.id, notice.ref))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setColor(color)
            .withLargeIcon(color, R.drawable.ic_tab_problems, stateColors().onState.toArgb())
            .setContentTitle(title)
            .text(text)
            // An update says nothing new enough to interrupt for.
            .setSilent(notice.change == Notice.Change.UPDATED || loud)
            .loud(loud)
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

    fun recovered(profile: Profile, ref: ObjectRef): Notification {
        val title = if (ref.description == null) R.string.notify_host_recovered else R.string.notify_service_recovered
        return builder(profile, NotifyChannel.RECOVERED, NotificationTarget(profile.id, ref))
            .setSmallIcon(R.drawable.ic_check)
            .setColor(stateColors().ok.toArgb())
            .withLargeIcon(stateColors().ok.toArgb(), R.drawable.ic_check, stateColors().onState.toArgb())
            .setContentTitle(emojis().recovered.before(context.getString(title, ref.describe(context))))
            .setSilent(true)
            // Good news goes stale: it clears itself, where a problem stays until dealt with.
            .setTimeoutAfter(AndroidNotifier.RECOVERY_SHOWN_FOR_MS)
            .build()
    }

    fun summary(profile: Profile, notice: Notice.Summary, loud: Boolean): Notification {
        val title = if (notice.first) R.plurals.notify_summary_first else R.plurals.notify_summary_more
        return builder(profile, NotifyChannel.SUMMARY, NotificationTarget(profile.id))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setContentTitle(context.resources.getQuantityString(title, notice.count, notice.count, profile.name))
            .text(context.getString(R.string.notify_summary_text))
            .setSilent(loud)
            .loud(loud)
            .build()
    }

    fun outage(profile: Profile, notice: Notice.Outage): Notification =
        builder(profile, NotifyChannel.OUTAGE, NotificationTarget(profile.id))
            .setSmallIcon(R.drawable.ic_tab_problems)
            .withLargeIcon(stateColors().pending.toArgb(), R.drawable.ic_no_signal, stateColors().onState.toArgb())
            .setContentTitle(emojis().outage.before(context.getString(R.string.notify_outage_title, profile.name)))
            .text(context.getString(notice.cause.sentence()))
            .setSilent(notice.quiet)
            .build()

    /** A problem's notification again, from what its button carried, with [change] made to it. */
    fun redrawn(request: ActionRequest, silent: Boolean, change: (NotificationCompat.Builder) -> Unit): Notification =
        builder(
            request.profileId,
            request.profileName,
            request.channelId,
            NotificationTarget(request.profileId, request.ref),
        )
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setColor(request.color)
            .withLargeIcon(request.color, R.drawable.ic_tab_problems, stateColors().onState.toArgb())
            .setContentTitle(request.title)
            .setSilent(silent)
            .also(change)
            .build()

    /** The entry Android needs to show a profile's notifications as a group. */
    fun groupEntry(profileId: Long, profileName: String): Notification =
        NotificationCompat.Builder(context, NotifyChannel.SUMMARY.id)
            .setSmallIcon(R.drawable.ic_tab_problems)
            .setContentTitle(profileName)
            .setContentIntent(open(NotificationTarget(profileId)))
            .setGroup(AndroidNotifier.group(profileId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .build()

    fun NotificationCompat.Builder.withActions(request: ActionRequest): NotificationCompat.Builder {
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

    fun NotificationCompat.Builder.text(text: String): NotificationCompat.Builder =
        if (text.isEmpty()) this else setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))

    /** What every notification of a profile has: where it goes, which profile, and its group. */
    private fun builder(profile: Profile, channel: NotifyChannel, target: NotificationTarget) =
        builder(profile.id, profile.name, channel.id, target)

    private fun builder(profileId: Long, profileName: String, channelId: String, target: NotificationTarget) =
        NotificationCompat.Builder(context, channelId)
            .setSubText(profileName)
            .setContentIntent(open(target))
            .setAutoCancel(true)
            .setGroup(AndroidNotifier.group(profileId))
            // The group's own entry never makes a sound; its members do.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)

    /**
     * A circle in [fill] with a glyph in it, unless the person turned it off. Not for a summary or
     * the group's entry: no state is true of them, as with the tint.
     */
    private fun NotificationCompat.Builder.withLargeIcon(
        @ColorInt fill: Int,
        @DrawableRes glyph: Int,
        @ColorInt glyphColor: Int,
    ): NotificationCompat.Builder =
        if (largeIcon()) setLargeIcon(LargeIcon.render(context, fill, glyph, glyphColor)) else this

    /** Swiping away a notification that a loud alert is sounding for stops the alert: it has been seen. */
    private fun NotificationCompat.Builder.loud(loud: Boolean): NotificationCompat.Builder =
        if (loud) setDeleteIntent(StopLoudAlertReceiver.pendingIntent(context)) else this

    private fun open(target: NotificationTarget): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        target.toIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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

    /**
     * The set the person chose, for the next notification. A second signal in the title for a shade
     * that will not show the tint (pgmac-net/nagios-android#81). A summary and the group's entry
     * have none, as they have no tint: no state is true of them.
     */
    private fun emojis(): EmojiTable = emojiSet().table(context.resources)
}
