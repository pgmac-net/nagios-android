// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import net.pgmac.nagwatch.profile.PollInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What is actually handed to WorkManager. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class WorkManagerPollSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = WorkManagerPollScheduler(context)

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun `a poll is periodic at the profile's interval, waits for a network, and says which profile`() {
        scheduler.schedule(7, PollInterval.THIRTY_MINUTES)

        val work = work(7).single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(TimeUnit.MINUTES.toMillis(30), work.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(WorkManagerPollScheduler.TAG in work.tags)
    }

    @Test
    fun `scheduling again changes the same job, and does not start a second`() {
        scheduler.schedule(7, PollInterval.FIFTEEN_MINUTES)
        val first = work(7).single()

        scheduler.schedule(7, PollInterval.ONE_HOUR)

        val second = work(7).single()
        assertEquals("the same job, so it keeps its place in the cycle", first.id, second.id)
        assertEquals(TimeUnit.MINUTES.toMillis(60), second.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `cancelling stops that profile's poll and no other`() {
        scheduler.schedule(7, PollInterval.FIFTEEN_MINUTES)
        scheduler.schedule(8, PollInterval.FIFTEEN_MINUTES)

        scheduler.cancel(7)

        assertEquals(WorkInfo.State.CANCELLED, work(7).single().state)
        assertEquals(WorkInfo.State.ENQUEUED, work(8).single().state)
    }

    @Test
    fun `cancelling a poll that was never scheduled is harmless`() {
        scheduler.cancel(7)

        assertTrue(work(7).isEmpty())
    }

    private fun work(profileId: Long): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPollScheduler.workName(profileId)).get()
}
