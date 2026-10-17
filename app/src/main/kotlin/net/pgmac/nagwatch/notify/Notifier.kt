// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.ui.theme.of

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

    /**
     * Shown while a loud alert sounds, to stop it. Each notification is posted silent: the sound is
     * the alert's. Default importance and not low, because a low one is filed under "Silent",
     * folded, with its Stop button out of sight (seen on a Pixel 8a).
     */
    SOUNDING("loud_alert_sounding", R.string.channel_sounding, NotificationManager.IMPORTANCE_DEFAULT),
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
            Severity.HOST_DOWN, Severity.CRITICAL -> CRITICAL
            Severity.WARNING -> WARNING
            Severity.HOST_UNREACHABLE, Severity.UNKNOWN -> UNKNOWN
        }
    }
}
