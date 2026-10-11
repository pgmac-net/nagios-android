// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.model.ObjectRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a tap carries into the app, and back out of the intent. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationTargetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a host, a service and a profile's problems each come back out as they went in`() {
        listOf(
            NotificationTarget(7, ObjectRef("web01")),
            NotificationTarget(7, ObjectRef("web01", "Disk /")),
            NotificationTarget(7),
        ).forEach { target ->
            assertEquals(target, NotificationTarget.from(target.toIntent(context)))
        }
    }

    @Test
    fun `names are whatever someone typed into a Nagios config, and arrive unchanged`() {
        AWKWARD.forEach { name ->
            val target = NotificationTarget(7, ObjectRef("host $name", name))

            assertEquals(target, NotificationTarget.from(target.toIntent(context)))
        }
    }

    @Test
    fun `an intent that is not from a notification opens nothing in particular`() {
        assertNull(NotificationTarget.from(null))
        assertNull(NotificationTarget.from(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
    }

    @Test
    fun `different targets are different intents to Android, so one notification's tap is never another's`() {
        val disk = NotificationTarget(7, ObjectRef("web01", "Disk /")).toIntent(context)

        listOf(
            NotificationTarget(7, ObjectRef("web01", "Load")),
            NotificationTarget(7, ObjectRef("web02", "Disk /")),
            NotificationTarget(7, ObjectRef("web01")),
            NotificationTarget(8, ObjectRef("web01", "Disk /")),
            NotificationTarget(7),
        ).forEach { other ->
            assertFalse("$other", disk.filterEquals(other.toIntent(context)))
        }
        assertTrue(disk.filterEquals(NotificationTarget(7, ObjectRef("web01", "Disk /")).toIntent(context)))
    }

    @Test
    fun `the intent reuses the open app`() {
        val flags = NotificationTarget(7).toIntent(context).flags

        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private companion object {
        val AWKWARD = listOf("Disk /var/lib (50%)", "a&b=c?d#e", "quo\"te's", "caf\u00e9 \u2603", "  spaces  ", "a+b c")
    }
}
