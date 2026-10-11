// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pgmac.nagwatch.profile.PollInterval
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The job as WorkManager runs it, in the real application: this is the one place that shows
 * the worker can find what it needs without being handed it.
 */
@RunWith(AndroidJUnit4::class)
class PollWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun `a job whose profile is gone ends quietly and takes itself off the schedule`() = runBlocking {
        WorkManagerPollScheduler(context).schedule(GONE, PollInterval.FIFTEEN_MINUTES)
        val worker = TestListenableWorkerBuilder<PollWorker>(context)
            .setInputData(workDataOf(PollWorker.PROFILE_ID to GONE))
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        assertEquals(WorkInfo.State.CANCELLED, work().single().state)
    }

    @Test
    fun `the job as scheduled runs for the profile it was scheduled for`() {
        WorkManagerPollScheduler(context).schedule(GONE, PollInterval.FIFTEEN_MINUTES)
        val scheduled = work().single()

        // WorkManager's test mode holds a job until told its conditions are met.
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(scheduled.id)

        // That profile does not exist, and only a job that knows which profile it is for cancels itself.
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                while (work().single().state != WorkInfo.State.CANCELLED) delay(POLL_MS)
            }
        }
    }

    private fun work(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPollScheduler.workName(GONE)).get()

    @Test
    fun `a job that does not say which profile does nothing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<PollWorker>(context).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    private companion object {
        const val GONE = 99L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
