// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The developer's "poll now", which exists in debug builds only. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class PollNowReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun `it queues one run of the background poll, with nothing to wait for`() {
        PollNowReceiver().onReceive(context, Intent().putExtra(PollNowReceiver.EXTRA_PROFILE, 7L))

        val queued = queued().single()
        // WorkManager's test mode runs unconstrained work on the spot, and outside the real
        // application the worker cannot find its dependencies: that it has already been tried is the point.
        assertTrue(queued.state.isFinished)
        assertEquals("once, not on a schedule", null, queued.periodicityInfo)
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, queued.constraints.requiredNetworkType)
    }

    @Test
    fun `without a profile it does nothing`() {
        PollNowReceiver().onReceive(context, Intent())

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `only adb's shell and the system may send it`() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, PollNowReceiver::class.java),
            0,
        )

        // Exported, or adb could not reach it; and guarded, so that nothing else can.
        assertTrue(receiver.exported)
        assertEquals("android.permission.DUMP", receiver.permission)
    }

    private fun queued(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosByTag(PollNowReceiver.TAG).get()
}
