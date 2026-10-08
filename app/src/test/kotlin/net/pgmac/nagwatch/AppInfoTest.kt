// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {
    @Test
    fun `version name is a semantic version`() {
        val versionName = AppInfo().versionName

        assertTrue(
            "versionName \"$versionName\" should look like 1.2.3",
            Regex("""\d+\.\d+\.\d+""").matches(versionName),
        )
    }
}
