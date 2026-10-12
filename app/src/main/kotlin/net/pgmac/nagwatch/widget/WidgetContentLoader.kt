// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.database.sqlite.SQLiteException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.cache.StatusCache

/** What one widget shows, and for which profile, at one moment. */
data class WidgetView(
    /** The profile the widget was set up for. Null if it has none, or the profile was deleted. */
    val profileId: Long?,
    val content: WidgetContent,
    /** When this was worked out, for "2h 04m" beside a problem. */
    val now: Instant,
)

/**
 * Works out what a widget shows from what is on disk: its profile, that profile's last good
 * poll, and what the most recent attempt came to. Nothing here touches the network; a widget
 * is drawn from the cache and the cache is kept fresh by polling.
 */
@Singleton
class WidgetContentLoader @Inject constructor(
    private val bindings: WidgetBindings,
    private val profiles: ProfileRepository,
    private val cache: StatusCache,
    private val clock: Clock,
) {
    suspend fun load(appWidgetId: Int): WidgetView {
        val now = clock.instant()
        val profile = bindings.profileOf(appWidgetId)?.let { profiles.get(it) }
        if (profile == null) return WidgetView(null, WidgetContent(WidgetState.PROFILE_GONE), now)
        val content = try {
            WidgetModel.of(profile, cache.loadPoll(profile.id), cache.loadAttempt(profile.id), now)
        } catch (_: SQLiteException) {
            // The cache cannot be read. That is "nothing known", said as such, not a crash on the home screen.
            WidgetContent(WidgetState.NO_DATA, profile.name)
        }
        return WidgetView(profile.id, content, now)
    }
}
