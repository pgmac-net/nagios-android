// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.Refreshed
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.cache.AnnouncedStore

/** What one background run came to. */
sealed interface PollRun {
    /** The profile is gone, or its notifications are off. Its job has been cancelled. */
    data object NotWanted : PollRun

    /** The app was already refreshing this profile. Nothing was done; the next run will see the result. */
    data object AlreadyRunning : PollRun

    /** Polled, but what was announced could not be read or saved, so nothing was decided. */
    data object NotRemembered : PollRun

    data class Decided(val decision: Decision) : PollRun
}

/**
 * One background poll of one profile: fetch, decide what it is worth saying, remember.
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
) {
    suspend fun poll(profileId: Long): PollRun {
        val profile = profiles.get(profileId)?.takeIf { it.notifications.enabled } ?: return notWanted(profileId)
        return when (val refreshed = statuses.refresh(profileId)) {
            null -> PollRun.AlreadyRunning

            is Refreshed.Fresh -> decide(profile, PollOutcome.Polled(refreshed.report))

            is Refreshed.Failed ->
                if (refreshed.error == StatusError.ProfileMissing) {
                    // Deleted while it was being polled.
                    notWanted(profileId)
                } else {
                    decide(profile, PollOutcome.Failed(refreshed.error))
                }
        }
    }

    private suspend fun decide(profile: Profile, outcome: PollOutcome): PollRun {
        val settings = NotifySettings(
            unhandledOnly = profile.notifications.unhandledOnly,
            tellUnreachable = profile.notifications.tellUnreachable,
        )
        return try {
            val decision = Announcer.decide(announced.load(profile.id), outcome, settings)
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
