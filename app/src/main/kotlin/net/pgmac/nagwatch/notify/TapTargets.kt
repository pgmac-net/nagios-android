// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.profile.ProfileRepository

/**
 * Decides whether a tapped notification still leads anywhere.
 *
 * A notification can outlive its profile (on a phone that had one stranded before the app
 * learned to clear them, or for the moment between deleting a profile and its notifications
 * going). Acting on its tap would select a profile that is gone and open a host that cannot
 * be shown. A profile that exists is opened whether or not its notifications are on: the
 * notification was true when it was posted, and the host is real.
 */
@Singleton
class TapTargets @Inject constructor(private val profiles: ProfileRepository) {
    /** [target] if its profile exists, otherwise null: the app then opens as it would from the launcher. */
    suspend fun existing(target: NotificationTarget): NotificationTarget? =
        target.takeIf { profiles.get(it.profileId) != null }
}
