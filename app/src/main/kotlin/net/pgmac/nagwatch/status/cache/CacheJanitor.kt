// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status.cache

import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.di.ApplicationScope
import net.pgmac.nagwatch.profile.ProfileRepository

/**
 * Removes cached status for profiles that no longer exist.
 *
 * Deleting a profile clears its cache straight away, but the profiles and the
 * cache are separate databases with no foreign key between them. If the app is
 * killed between the two steps the cache rows would stay for ever. This runs
 * once at startup to catch that.
 */
@Singleton
class CacheJanitor @Inject constructor(
    private val profiles: ProfileRepository,
    private val cache: StatusCache,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { sweep() }
    }

    suspend fun sweep() {
        try {
            cache.sweepOrphans(profiles.observeProfiles().first().map { it.id })
        } catch (_: SQLiteException) {
            // Housekeeping only. A failure here costs some disk space until the next start.
        }
    }
}
