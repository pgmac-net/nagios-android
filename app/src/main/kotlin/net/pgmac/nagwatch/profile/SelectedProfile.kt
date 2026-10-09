// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Something that must tidy up when a profile is deleted, such as a cache keyed by its id. */
fun interface ProfileCleanup {
    suspend fun onProfileDeleted(profileId: Long)
}

/**
 * Which profile the user is looking at. One shared answer for every screen,
 * remembered across restarts so the app reopens where it was left.
 */
interface SelectedProfile {
    /** The chosen profile's id, or null if none has been chosen yet. It may name a profile that no longer exists. */
    val id: Flow<Long?>

    suspend fun select(profileId: Long)
}

@Singleton
class DataStoreSelectedProfile @Inject constructor(private val store: DataStore<Preferences>) : SelectedProfile {
    override val id: Flow<Long?> = store.data.map { it[KEY] }.distinctUntilChanged()

    override suspend fun select(profileId: Long) {
        store.edit { it[KEY] = profileId }
    }

    private companion object {
        val KEY = longPreferencesKey("selected_profile_id")
    }
}
