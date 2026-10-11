// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** The permission's name. Spelled out because the constant is newer than the oldest Android supported. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** The first Android that has it. Below this a local address needs nothing but `INTERNET`. */
private const val FIRST_ANDROID_WITH_PERMISSION = 37

@Singleton
class AndroidLocalNetworkPermission @Inject constructor(@ApplicationContext private val context: Context) :
    LocalNetworkPermission {
    override fun isHeld(): Boolean = Build.VERSION.SDK_INT < FIRST_ANDROID_WITH_PERMISSION ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

@Singleton
class DataStoreLocalNetworkHistory @Inject constructor(private val store: DataStore<Preferences>) :
    LocalNetworkHistory {
    override suspend fun explained(): Boolean = store.data.first()[EXPLAINED] ?: false

    override suspend fun markExplained() {
        store.edit { it[EXPLAINED] = true }
    }

    override suspend fun exhausted(): Boolean = store.data.first()[EXHAUSTED] ?: false

    override suspend fun setExhausted(exhausted: Boolean) {
        store.edit { it[EXHAUSTED] = exhausted }
    }

    private companion object {
        val EXPLAINED = booleanPreferencesKey("local_network_explained")
        val EXHAUSTED = booleanPreferencesKey("local_network_exhausted")
    }
}
