// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.notify.EmojiSet
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The emoji choice as the person meets it. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chosen = mutableListOf<EmojiSet>()

    private fun show(set: EmojiSet) = composeRule.setContent {
        NagwatchTheme { SettingsContent(set, onChoose = { chosen += it }, resources = context.resources) }
    }

    @Test
    fun `every set is offered, each with a look at it, and the current one is marked`() {
        show(EmojiSet.SIGNS)

        EmojiSet.entries.forEach { composeRule.onNodeWithTag(SettingsTags.emojiSet(it)).assertExists() }
        composeRule.onNodeWithText("🚨 ⚠️ ❓ ✅").assertExists()
        composeRule.onNodeWithText("Coloured circles").assertExists()
        composeRule.onNodeWithTag(SettingsTags.emojiSet(EmojiSet.SIGNS)).assertIsSelected()
    }

    @Test
    fun `pressing a set chooses it`() {
        show(EmojiSet.CIRCLES)

        composeRule.onNodeWithTag(SettingsTags.emojiSet(EmojiSet.FACES)).performScrollTo().performClick()

        assertEquals(listOf(EmojiSet.FACES), chosen)
    }
}
