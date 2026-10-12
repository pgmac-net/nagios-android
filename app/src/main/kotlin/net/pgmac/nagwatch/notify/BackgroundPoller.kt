// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.Refreshed
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.cache.AnnouncedStore
import net.pgmac.nagwatch.widget.WidgetBindings

/** What one background run came to. */
sealed interface PollRun {
    /** The profile is gone, or has neither notifications on nor a widget. Its job has been cancelled. */
    data object NotWanted : PollRun

    /** Polled for a widget only: the cache is fresh, and nothing was announced because notifications are off. */
    data object PolledQuietly : PollRun

    /** The app was already refreshing this profile. Nothing was done; the next run will see the result. */
    data object AlreadyRunning : PollRun

    /** Polled, but what was announced could not be read or saved, so nothing was decided. */
    data object NotRemembered : PollRun

    data class Decided(val decision: Decision) : PollRun
}

/**
 * One background poll of one profile: fetch, and, if its notifications are on, decide what it is
 * worth saying, say it, remember. A profile polled only because it has a widget is fetched and
 * nothing more.
 *
 * The fetch is the same one the screens use ([StatusRepository.refresh]), so the cache is
 * written and an open app sees the result: one request serves the app, the notifications and,
 * later, the widget.
 */
@Singleton
class BackgroundPoller @Inject constructor(
    private val profiles: ProfileRepository,
    private val statuses: StatusRepository,
    private val announced: AnnouncedStore,
    private val scheduler: PollScheduler,
    private val notifier: Notifier,
    private val bindings: WidgetBindings,
) {
    suspend fun poll(profileId: Long): PollRun {
        val profile = profiles.get(profileId) ?: return notWanted(profileId)
        // Polled for either reason; announced only for the first. A widget never turns notifications on.
        val announces = profile.notifications.enabled
        if (!announces && profileId !in bindings.watched.first()) return notWanted(profileId)
        return when (val refreshed = statuses.refresh(profileId)) {
            null -> PollRun.AlreadyRunning

            is Refreshed.Fresh -> said(profile, announces, PollOutcome.Polled(refreshed.report))

            is Refreshed.Failed ->
                if (refreshed.error == StatusError.ProfileMissing) {
                    // Deleted while it was being polled.
                    notWanted(profileId)
                } else {
                    said(profile, announces, PollOutcome.Failed(refreshed.error))
                }
        }
    }

    private suspend fun said(profile: Profile, announces: Boolean, outcome: PollOutcome): PollRun =
        if (announces) decide(profile, outcome) else PollRun.PolledQuietly

    private suspend fun decide(profile: Profile, outcome: PollOutcome): PollRun {
        val settings = NotifySettings(
            unhandledOnly = profile.notifications.unhandledOnly,
            tellUnreachable = profile.notifications.tellUnreachable,
        )
        return try {
            val decision = Announcer.decide(announced.load(profile.id), outcome, settings)
            // Said before it is remembered as said. If the app dies between the two, the next run
            // says it again, which replaces the same notification; the other order would lose it.
            notifier.deliver(profile, decision)
            announced.save(profile.id, decision.state)
            PollRun.Decided(decision)
        } catch (_: SQLiteException) {
            // The memory could not be read or written (a full disk, say). The poll itself is
            // done and cached; the next run decides again from whatever was last saved.
            PollRun.NotRemembered
        }
    }

    /** The schedule outlived the reason for it: stop it here, where it was noticed. */
    private fun notWanted(profileId: Long): PollRun {
        scheduler.cancel(profileId)
        return PollRun.NotWanted
    }
}
