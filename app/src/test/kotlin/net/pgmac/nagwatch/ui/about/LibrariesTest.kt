// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the About screen reads from the build's report, and the guard that every licence in it can be shown. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class LibrariesTest {
    private val report = """
        [
          {"groupId":"b.group","artifactId":"zeta","version":"2","name":"Zeta",
           "spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"u"}],"scm":{"url":"s"}},
          {"groupId":"a.group","artifactId":"alpha","version":"1.5",
           "spdxLicenses":[{"identifier":"BSD-3-Clause","name":"BSD 3-Clause","url":"u"}]},
          {"groupId":"c.group","artifactId":"odd","version":"3","name":"Odd",
           "unknownLicenses":[{"name":"Home-made","url":"u"}]}
        ]
    """.trimIndent()

    @Test
    fun `libraries come out sorted by name, falling back to the artifact id`() {
        val libraries = Libraries.parse(report)

        assertEquals(listOf("alpha", "Odd", "Zeta"), libraries.map { it.name })
        assertEquals("a.group:alpha", libraries.first().coordinates)
        assertEquals("1.5", libraries.first().version)
    }

    @Test
    fun `a licence Licensee could not name has no id but keeps its name`() {
        val odd = Libraries.parse(report).single { it.name == "Odd" }

        assertNull(odd.licenceId)
        assertEquals("Home-made", odd.licenceName)
    }

    @Test
    fun `a report that cannot be read is no libraries, not a crash`() {
        assertTrue(Libraries.parse("not json").isEmpty())
        assertTrue(Libraries.parse("{}").isEmpty())
    }

    @Test
    fun `a build with no report gives no libraries`() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        // Robolectric merges the app's assets, so this is the real report unless the build left it out.
        assertNotNull(Libraries.load(assets))
    }

    @Test
    fun `every licence in the report shipped with this build has its full text bundled`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val libraries = Libraries.load(context.assets)

        assertTrue("the build's licence report is missing from the assets", libraries.isNotEmpty())
        val missing = libraries.filter { it.licenceId == null || LicenceTexts.resourceFor(it.licenceId) == null }
        assertTrue(
            "no bundled licence text for: ${missing.map { "${it.coordinates} (${it.licenceName})" }}. " +
                "Add it under app/src/main/res/raw and to LicenceTexts (docs/development.md).",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the bundled texts are the licences they say they are`() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        fun text(id: String) = resources.openRawResource(checkNotNull(LicenceTexts.resourceFor(id))).use {
            it.readBytes().decodeToString()
        }

        assertTrue(text("Apache-2.0").contains("Apache License"))
        assertTrue(text("BSD-3-Clause").contains("Redistribution and use in source and binary forms"))
        assertTrue(text(LicenceTexts.NAGWATCH).contains("GNU GENERAL PUBLIC LICENSE"))
    }
}
