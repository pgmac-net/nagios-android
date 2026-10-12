// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.di.ApplicationScope
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.cache.AnnouncedStore
import net.pgmac.nagwatch.widget.WidgetBindings

/** Puts a profile's background poll on Android's schedule, and takes it off. */
interface PollScheduler {
    /** Safe to repeat: a profile has one poll, and scheduling it again only changes how often. */
    fun schedule(profileId: Long, interval: PollInterval)

    fun cancel(profileId: Long)
}

/**
 * One periodic WorkManager job per profile, under a name made from the profile's id.
 *
 * It waits for a network: a phone with none cannot reach Nagios, and a poll that was never
 * tried is not a failed poll. Android runs it about every [PollInterval], never exactly, and
 * later while the phone dozes.
 */
@Singleton
class WorkManagerPollScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    PollScheduler {
    override fun schedule(profileId: Long, interval: PollInterval) {
        val request = PeriodicWorkRequestBuilder<PollWorker>(interval.minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(PollWorker.PROFILE_ID to profileId))
            .addTag(TAG)
            .build()
        // UPDATE keeps the job's place in its cycle: opening the app does not make every profile poll at once.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(workName(profileId), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel(profileId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(profileId))
    }

    companion object {
        /** On every poll, so that all of them can be found without knowing the profiles. */
        const val TAG = "poll"

        fun workName(profileId: Long): String = "poll-$profileId"
    }
}

/**
 * Keeps the schedule in step with the profiles, and acts on a profile leaving it.
 *
 * Two things are true of a profile, and they are no longer the same thing:
 *
 * - it is **polled** in the background while it has notifications on *or* a widget on the home
 *   screen (docs/adr/0009);
 * - it **announces** only while its notifications are on. A widget never turns them on.
 *
 * It follows the stored profiles and widgets, not the screens that change them, so there is one
 * place where a job is scheduled or cancelled however the change was made. A job that outlives
 * its reason anyway (the app died between the two) cancels itself when it next runs.
 *
 * When a profile stops announcing, by its notifications being switched off or by being deleted,
 * everything it has showing is taken down and what was announced for it is forgotten, so that
 * switching back on is a first poll. On the first look after the app starts, whatever an earlier
 * run left behind is swept up (docs/adr/0008).
 */
@Singleton
class PollSchedule @Inject constructor(
    private val profiles: ProfileRepository,
    private val bindings: WidgetBindings,
    private val scheduler: PollScheduler,
    private val notifier: Notifier,
    private val announced: AnnouncedStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** What is wanted of one profile: how often it is polled (null for not at all), and whether it announces. */
    private data class Watch(val interval: PollInterval?, val announces: Boolean)

    /** What was last acted on, by profile. Null is "not looked yet", which is what makes the first look a sweep. */
    private var applied: Map<Long, Watch>? = null

    fun start() {
        scope.launch {
            profiles.observeProfiles().combine(bindings.watched, ::wanted).distinctUntilChanged().collect(::apply)
        }
    }

    private fun wanted(all: List<Profile>, withWidgets: Set<Long>): Map<Long, Watch> = all.associate { profile ->
        val announces = profile.notifications.enabled
        val polled = announces || profile.id in withWidgets
        profile.id to Watch(profile.notifications.interval.takeIf { polled }, announces)
    }

    private suspend fun apply(wanted: Map<Long, Watch>) {
        val before = applied
        if (before == null) {
            // The first look since the app started: what is showing may have been left by a
            // version that did not clear, or by a run that was killed half way.
            notifier.sweep(wanted.filterValues { it.announces }.keys)
            wanted.filterValues { !it.announces }.keys.forEach { forget(it) }
        } else {
            (before.keys - wanted.keys).forEach { id ->
                scheduler.cancel(id)
                stopAnnouncing(id)
            }
        }
        wanted.forEach { (id, now) ->
            val was = before?.get(id)
            if (was == null || was.interval != now.interval) {
                if (now.interval != null) scheduler.schedule(id, now.interval) else scheduler.cancel(id)
            }
            if (was != null && was.announces && !now.announces) stopAnnouncing(id)
        }
        applied = wanted
    }

    private suspend fun stopAnnouncing(profileId: Long) {
        notifier.clear(profileId)
        forget(profileId)
    }

    /** What was announced is a convenience: a database that cannot be written costs a summary later, not the app. */
    private suspend fun forget(profileId: Long) {
        try {
            announced.forget(profileId)
        } catch (_: SQLiteException) {
            // The next switch-on is then not a first poll. Nothing depends on it.
        }
    }
}
