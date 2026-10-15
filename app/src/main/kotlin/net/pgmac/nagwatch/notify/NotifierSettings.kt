// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How notifications look, as the person set it: for the app, not for one profile. */
interface NotifierSettings {
    val emojiSet: Flow<EmojiSet>

    suspend fun setEmojiSet(set: EmojiSet)

    /** A coloured picture as the notification's large icon. On unless the person turned it off. */
    val largeIcon: Flow<Boolean>

    suspend fun setLargeIcon(on: Boolean)
}

@Singleton
class DataStoreNotifierSettings @Inject constructor(private val store: DataStore<Preferences>) : NotifierSettings {
    override val emojiSet: Flow<EmojiSet> = store.data.map { EmojiSet.of(it[KEY]) }.distinctUntilChanged()

    override suspend fun setEmojiSet(set: EmojiSet) {
        store.edit { it[KEY] = set.id }
    }

    override val largeIcon: Flow<Boolean> = store.data.map { it[LARGE_ICON_KEY] ?: true }.distinctUntilChanged()

    override suspend fun setLargeIcon(on: Boolean) {
        store.edit { it[LARGE_ICON_KEY] = on }
    }

    private companion object {
        val KEY = stringPreferencesKey("notification_emoji_set")
        val LARGE_ICON_KEY = booleanPreferencesKey("notification_large_icon")
    }
}
