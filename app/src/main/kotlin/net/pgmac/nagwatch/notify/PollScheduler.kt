// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.di.ApplicationScope
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository

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
 * Keeps the schedule in step with the profiles: a profile is polled in the background exactly
 * while its notifications are on.
 *
 * It follows the stored profiles, not the screens that change them, so there is one place
 * where a job is scheduled or cancelled however the change was made. A job that outlives its
 * profile anyway (the app died between the two) cancels itself when it next runs.
 */
@Singleton
class PollSchedule @Inject constructor(
    private val profiles: ProfileRepository,
    private val scheduler: PollScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** What was last asked of the scheduler, by profile; null is "not polled". */
    private var applied: Map<Long, PollInterval?> = emptyMap()

    fun start() {
        scope.launch {
            profiles.observeProfiles().map(::wanted).distinctUntilChanged().collect(::apply)
        }
    }

    private fun wanted(all: List<Profile>): Map<Long, PollInterval?> =
        all.associate { it.id to it.notifications.interval.takeIf { _ -> it.notifications.enabled } }

    private fun apply(wanted: Map<Long, PollInterval?>) {
        (applied.keys - wanted.keys).forEach(scheduler::cancel)
        wanted.forEach { (id, interval) ->
            when {
                id in applied && applied[id] == interval -> Unit
                interval != null -> scheduler.schedule(id, interval)
                else -> scheduler.cancel(id)
            }
        }
        applied = wanted
    }
}
