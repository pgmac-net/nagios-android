// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Which profile each widget on the home screen shows.
 *
 * A widget is identified by the number Android gave it. One widget shows one profile; a
 * profile may have several widgets. The profile's id is all that is kept: a widget whose
 * profile has since been deleted still has its entry, and says the profile is gone.
 */
interface WidgetBindings {
    /** The profiles that have at least one widget. A profile here is polled in the background. */
    val watched: Flow<Set<Long>>

    suspend fun profileOf(appWidgetId: Int): Long?

    suspend fun bind(appWidgetId: Int, profileId: Long)

    /** Forgets widgets that have been removed from the home screen. */
    suspend fun unbind(appWidgetIds: Collection<Int>)

    /**
     * Forgets every widget that is not one of [appWidgetIds]: the ones Android says exist. For
     * app start, in case a widget was removed while the app could not be told.
     */
    suspend fun retainOnly(appWidgetIds: Collection<Int>)
}

@Singleton
class DataStoreWidgetBindings @Inject constructor(private val store: DataStore<Preferences>) : WidgetBindings {
    override val watched: Flow<Set<Long>> =
        store.data.map { preferences -> bindings(preferences).values.toSet() }.distinctUntilChanged()

    override suspend fun profileOf(appWidgetId: Int): Long? = store.data.first()[key(appWidgetId)]

    override suspend fun bind(appWidgetId: Int, profileId: Long) {
        store.edit { it[key(appWidgetId)] = profileId }
    }

    override suspend fun unbind(appWidgetIds: Collection<Int>) {
        store.edit { preferences -> appWidgetIds.forEach { preferences.remove(key(it)) } }
    }

    override suspend fun retainOnly(appWidgetIds: Collection<Int>) {
        val keep = appWidgetIds.map { key(it) }.toSet()
        store.edit { preferences ->
            bindings(preferences).keys.filterNot { it in keep }.forEach { preferences.remove(it) }
        }
    }

    private fun bindings(preferences: Preferences): Map<Preferences.Key<Long>, Long> = buildMap {
        preferences.asMap().forEach { (key, value) ->
            if (key.name.startsWith(PREFIX) && value is Long) put(longPreferencesKey(key.name), value)
        }
    }

    private fun key(appWidgetId: Int) = longPreferencesKey("$PREFIX$appWidgetId")

    private companion object {
        const val PREFIX = "widget_"
    }
}
