// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pgmac.nagwatch.notify.PollNow
import net.pgmac.nagwatch.notify.WorkManagerPollScheduler
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.ui.widget.WidgetConfigActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The parts of the widget that Android makes itself and that find what they need from the
 * application: run here in the real one, like the poll worker.
 */
@RunWith(AndroidJUnit4::class)
class WidgetInAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bindings get() = StatusWidget.dependencies(context).widgetBindings()

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun `removing a widget forgets it, so its profile stops being watched for it`() = runBlocking {
        bindings.bind(41, 7)
        bindings.bind(42, 7)

        StatusWidgetReceiver.forget(context, intArrayOf(41))

        assertNull(bindings.profileOf(41))
        assertEquals(7L, bindings.profileOf(42))
    }

    @Test
    fun `Refresh queues one poll of the profile, with nothing to wait for, and tapping again adds none`() =
        runBlocking {
            val action = RefreshAction()

            action.onAction(context, object : GlanceId {}, actionParametersOf(RefreshAction.PROFILE to 7L))
            action.onAction(context, object : GlanceId {}, actionParametersOf(RefreshAction.PROFILE to 7L))

            val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PollNow.workName(7)).get()
            assertEquals(1, queued.size)
            assertEquals(NetworkType.NOT_REQUIRED, queued.single().constraints.requiredNetworkType)
            assertNull("once, not on a schedule", queued.single().periodicityInfo)
        }

    @Test
    fun `the poll that Refresh queues is for that profile`() {
        // The profile does not exist, and a poll that knows which profile it is for takes that
        // profile's scheduled job off: the one visible sign, from here, of what it was told.
        WorkManagerPollScheduler(context).schedule(GONE, PollInterval.FIFTEEN_MINUTES)

        runBlocking {
            RefreshAction().onAction(context, object : GlanceId {}, actionParametersOf(RefreshAction.PROFILE to GONE))
            withTimeout(TIMEOUT_MS) {
                while (scheduled(GONE).single().state != WorkInfo.State.CANCELLED) delay(POLL_MS)
            }
        }
    }

    private fun scheduled(profileId: Long): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPollScheduler.workName(profileId)).get()

    @Test
    fun `Refresh without a profile does nothing`() = runBlocking {
        RefreshAction().onAction(context, object : GlanceId {}, actionParametersOf())

        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(PollNow.TAG).get().isEmpty())
    }

    @Test
    fun `the configuration screen refuses a widget number that is not one of this app's widgets`() {
        listOf(
            Intent(),
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 9_999),
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        ).forEach { intent ->
            val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).create().get()

            assertTrue("it has nothing to configure, so it leaves", activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    @Test
    fun `it refuses another app's widget even though the number is real`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).addBoundWidget(
            55,
            AppWidgetProviderInfo().apply {
                provider =
                    ComponentName("org.example.other", "Widget")
            },
        )
        val intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 55)

        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).create().get()

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun `for one of this app's widgets it stays open, and says cancelled until a profile is chosen`() {
        val manager = AppWidgetManager.getInstance(context)
        val ours = ComponentName(context, StatusWidgetReceiver::class.java)
        shadowOf(manager).addBoundWidget(56, AppWidgetProviderInfo().apply { provider = ours })
        val intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 56)

        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).create().get()

        assertFalse(activity.isFinishing)
        assertEquals(
            "backing out removes a widget that was being placed",
            Activity.RESULT_CANCELED,
            shadowOf(activity).resultCode,
        )
        assertEquals(56, shadowOf(activity).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    private companion object {
        const val GONE = 99L
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
