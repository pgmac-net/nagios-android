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
import kotlinx.coroutines.runBlocking
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
    fun `the app declares the internet and, now, local network access, and nothing else`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            // AndroidX adds a permission of its own, named for the package, to guard its receivers.
            .filterNot { it.startsWith(context.packageName) }
            .toSet()

        // The README says this is everything Nagwatch asks Android for. A new line here is a new promise to keep.
        assertEquals(setOf("android.permission.INTERNET", LOCAL_NETWORK_PERMISSION), declared)
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
        scope.cancel()

        // A new process over the same file.
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = DataStoreLocalNetworkHistory(PreferenceDataStoreFactory.create(scope = restartScope) { file })
            assertTrue(second.explained())
            assertTrue(second.exhausted())

            second.setExhausted(false)
            assertFalse("a grant puts it right again", second.exhausted())
            assertTrue("and does not forget that it was explained", second.explained())
        } finally {
            restartScope.cancel()
        }
    }
}
