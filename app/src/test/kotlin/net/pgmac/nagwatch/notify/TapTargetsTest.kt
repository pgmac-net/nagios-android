// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A tap is acted on only if what it was about is still there. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class TapTargetsTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val taps = TapTargets(profiles)

    @After
    fun close() = database.close()

    @Test
    fun `a profile that exists is opened, whether its notifications are on or off`() = runBlocking {
        val on = profiles.save(draftWith(NotificationSettings(enabled = true)))
        val off = profiles.save(draftWith(NotificationSettings(enabled = false), name = "Office"))

        val onTarget = NotificationTarget(on, ObjectRef("web01", "Disk /"))
        val offTarget = NotificationTarget(off, ObjectRef("web01"))

        assertEquals(onTarget, taps.existing(onTarget))
        assertEquals(
            "the host is real, and the notification was true when it was posted",
            offTarget,
            taps.existing(offTarget),
        )
    }

    @Test
    fun `a profile that is gone is not opened`() = runBlocking {
        assertNull(taps.existing(NotificationTarget(42, ObjectRef("web01", "Disk /"))))
        assertNull(taps.existing(NotificationTarget(42)))
    }

    @Test
    fun `a profile deleted after the notification was posted is not opened`() = runBlocking {
        val id = profiles.save(draftWith())
        val target = NotificationTarget(id, ObjectRef("web01"))
        assertEquals(target, taps.existing(target))

        profiles.delete(id)

        assertNull(taps.existing(target))
    }
}
