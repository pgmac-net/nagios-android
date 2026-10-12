// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** The permission's name. Spelled out because the constant is newer than the oldest Android supported. */
const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

/** Whether Android will show this app's notifications at all. */
fun interface NotificationPermission {
    fun isHeld(): Boolean
}

/** What the app remembers about asking, for the whole install: the permission belongs to the app. */
interface NotificationHistory {
    /** Android has stopped showing its prompt, so the only way left is the app's settings page. */
    suspend fun exhausted(): Boolean

    suspend fun setExhausted(exhausted: Boolean)
}

/**
 * Android's permission to post notifications, in one place.
 *
 * It is asked for when the person switches notifications on for a profile, and not before: a
 * prompt at first launch would be asking for something the app has given no reason to want.
 */
@Singleton
class NotificationAccess @Inject constructor(
    private val permission: NotificationPermission,
    private val history: NotificationHistory,
) {
    fun isHeld(): Boolean = permission.isHeld()

    /**
     * The system prompt came back. [canAskAgain] is Android's own `shouldShowRequestPermissionRationale`
     * after the answer: false after a refusal means it will not show the prompt again.
     */
    suspend fun answered(granted: Boolean, canAskAgain: Boolean) = history.setExhausted(!granted && !canAskAgain)

    /** True when the way to allow notifications is the settings page, not a prompt Android would ignore. */
    suspend fun exhausted(): Boolean = history.exhausted()
}

/**
 * One question covers every Android version: below 13 there is no permission and notifications
 * are allowed unless the person turned them off for the app; from 13 this is false until the
 * permission is granted.
 */
@Singleton
class AndroidNotificationPermission @Inject constructor(@ApplicationContext private val context: Context) :
    NotificationPermission {
    override fun isHeld(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

@Singleton
class DataStoreNotificationHistory @Inject constructor(private val store: DataStore<Preferences>) :
    NotificationHistory {
    override suspend fun exhausted(): Boolean = store.data.first()[EXHAUSTED] ?: false

    override suspend fun setExhausted(exhausted: Boolean) {
        store.edit { it[EXHAUSTED] = exhausted }
    }

    private companion object {
        val EXHAUSTED = booleanPreferencesKey("notifications_exhausted")
    }
}
