// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pgmac.nagwatch.notify.NOTIFICATION_PERMISSION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Android half of local network access: what the app declares, what it holds, and what it remembers. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class AndroidLocalNetworkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    @Test
    fun `the app asks Android for what the README says it does, and nothing else`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            // AndroidX adds a permission of its own, named for the package, to guard its receivers.
            .filterNot { it.startsWith(context.packageName) }
            .toSet()

        // The README says this is everything Nagwatch asks Android for. A new line here is a new promise to keep.
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                LOCAL_NETWORK_PERMISSION,
                NOTIFICATION_PERMISSION,
                // Background polling's own (WorkManager): stay awake for one poll, wait for a
                // network, and put the schedule back after a restart. None is ever asked of the person.
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                // Loud alerts (docs/adr/0013): a foreground service to play in, the exact-alarm
                // permission Android 17 asks of anything heard from the background (the person
                // grants it, when they switch loud alerts on), and the buzz.
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.VIBRATE",
            ),
            declared,
        )
    }

    @Test
    fun `where the permission does not exist it is never missing`() {
        // These tests run as Android 15. There, a local address needs nothing but INTERNET, and
        // asking for a permission the system has never heard of would only confuse.
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        assertTrue(AndroidLocalNetworkPermission(context).isHeld())
    }

    @Test
    fun `what was explained and what Android has stopped offering are remembered, across a restart`() = runBlocking {
        val file = File(folder.root, "settings.preferences_pb")
        val first = DataStoreLocalNetworkHistory(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertFalse(first.explained())
        assertFalse(first.exhausted())

        first.markExplained()
        first.setExhausted(true)
        // A store is only allowed once per file at a time: close this one before "restarting".
        scope.coroutineContext.job.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = DataStoreLocalNetworkHistory(PreferenceDataStoreFactory.create(scope = restartScope) { file })
            // The first store releases the file from its own scope's completion, which can trail the join.
            assertTrue(whenTheFileIsFree { second.explained() })
            assertTrue(second.exhausted())

            second.setExhausted(false)
            assertFalse("a grant puts it right again", second.exhausted())
            assertTrue("and does not forget that it was explained", second.explained())
        } finally {
            restartScope.cancel()
        }
    }

    /** Retries while the previous store still holds the file; anything else is a real failure. */
    private suspend fun <T> whenTheFileIsFree(read: suspend () -> T): T = withTimeout(FREE_WITHIN_MS) {
        while (true) {
            try {
                return@withTimeout read()
            } catch (_: IllegalStateException) {
                delay(RETRY_MS)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private companion object {
        const val FREE_WITHIN_MS = 10_000L
        const val RETRY_MS = 20L
    }
}
