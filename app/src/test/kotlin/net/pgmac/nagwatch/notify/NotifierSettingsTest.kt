// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The emoji set as kept in the app's settings. */
class NotifierSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }
    private val settings by lazy { DataStoreNotifierSettings(store) }

    @After
    fun stop() = scope.cancel()

    @Test
    fun `nothing chosen is the coloured circles`() = runBlocking {
        assertEquals(EmojiSet.CIRCLES, settings.emojiSet.first())
    }

    @Test
    fun `a choice is kept, and can be changed`() = runBlocking {
        settings.setEmojiSet(EmojiSet.SIGNS)
        assertEquals(EmojiSet.SIGNS, settings.emojiSet.first())

        settings.setEmojiSet(EmojiSet.NONE)
        assertEquals(EmojiSet.NONE, settings.emojiSet.first())
    }

    @Test
    fun `a stored id this version does not know is the default and not a crash`() = runBlocking {
        store.edit { it[stringPreferencesKey("notification_emoji_set")] = "from-the-future" }
        assertEquals(EmojiSet.CIRCLES, settings.emojiSet.first())
    }
}
