// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NagiosVersionTest {
    @Test
    fun `versions before 4_4 are older than tested`() {
        listOf("4.0.7", "4.3.4", "4.3", "3.5.1", "4.0.8 ").forEach { version ->
            assertTrue(version, NagiosVersion.isOlderThanTested(version))
        }
    }

    @Test
    fun `4_4 and later are not`() {
        listOf("4.4.0", "4.4.14", "4.5.9", "4.10.1", "5.0.0", "4.5").forEach { version ->
            assertFalse(version, NagiosVersion.isOlderThanTested(version))
        }
    }

    @Test
    fun `a version with a suffix is read by its numbers`() {
        assertTrue(NagiosVersion.isOlderThanTested("4.3rc1"))
        assertFalse(NagiosVersion.isOlderThanTested("4.5.9-custom"))
    }

    @Test
    fun `a version that cannot be read gets no note rather than a guess`() {
        listOf("", "unknown", "Nagios Core", "v4.3").forEach { version ->
            assertFalse(version, NagiosVersion.isOlderThanTested(version))
        }
    }
}
