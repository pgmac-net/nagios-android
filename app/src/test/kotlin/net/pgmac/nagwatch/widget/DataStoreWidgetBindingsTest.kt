// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Which widget shows which profile, as kept in the app's settings. */
class DataStoreWidgetBindingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }
    private val bindings by lazy { DataStoreWidgetBindings(store) }

    @After
    fun stop() = scope.cancel()

    @Test
    fun `with no widgets nothing is watched and nothing is found`() = runBlocking {
        assertEquals(emptySet<Long>(), bindings.watched.first())
        assertNull(bindings.profileOf(41))
    }

    @Test
    fun `a widget remembers its profile, and can be pointed at another`() = runBlocking {
        bindings.bind(41, HOME)
        assertEquals(HOME, bindings.profileOf(41))

        bindings.bind(41, OFFICE)
        assertEquals(OFFICE, bindings.profileOf(41))
        assertEquals(setOf(OFFICE), bindings.watched.first())
    }

    @Test
    fun `a profile is watched while it has any widget, and not after its last one goes`() = runBlocking {
        bindings.bind(41, HOME)
        bindings.bind(42, HOME)
        bindings.bind(43, OFFICE)
        assertEquals(setOf(HOME, OFFICE), bindings.watched.first())

        bindings.unbind(listOf(41))
        assertEquals("one widget of Home is left", setOf(HOME, OFFICE), bindings.watched.first())

        bindings.unbind(listOf(42, 43))
        assertEquals(emptySet<Long>(), bindings.watched.first())
        assertNull(bindings.profileOf(42))
    }

    @Test
    fun `removing a widget that was never there is harmless`() = runBlocking {
        bindings.bind(41, HOME)

        bindings.unbind(listOf(99))

        assertEquals(HOME, bindings.profileOf(41))
    }

    @Test
    fun `at app start the widgets Android no longer has are forgotten, and the rest kept`() = runBlocking {
        bindings.bind(41, HOME)
        bindings.bind(42, OFFICE)
        bindings.bind(43, OFFICE)

        bindings.retainOnly(listOf(41, 43, 77))

        assertEquals(HOME, bindings.profileOf(41))
        assertNull(bindings.profileOf(42))
        assertEquals(OFFICE, bindings.profileOf(43))

        bindings.retainOnly(emptyList())
        assertEquals(emptySet<Long>(), bindings.watched.first())
    }

    @Test
    fun `the app's other settings in the same store are not widgets and are left alone`() = runBlocking {
        val selected = longPreferencesKey("selected_profile")
        val flag = booleanPreferencesKey("widget_hint_shown")
        store.edit {
            it[selected] = 9L
            it[flag] = true
        }
        bindings.bind(41, HOME)

        assertEquals("a setting that is not a widget is not a watched profile", setOf(HOME), bindings.watched.first())

        bindings.retainOnly(emptyList())
        assertEquals(9L, store.data.first()[selected])
        assertEquals("not a number, so not one of ours to remove", true, store.data.first()[flag])
    }

    private companion object {
        const val HOME = 1L
        const val OFFICE = 2L
    }
}
