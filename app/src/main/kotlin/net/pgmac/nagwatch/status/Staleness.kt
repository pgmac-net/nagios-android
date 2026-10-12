// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import java.time.Duration
import java.time.Instant
import net.pgmac.nagwatch.profile.PollInterval

/**
 * When what is held is too old to pass off as current: twice the profile's interval.
 *
 * Once is not enough. Android runs a poll about every interval, never exactly, so data that is
 * one interval old is simply what a healthy poll looks like just before the next. Twice means a
 * poll has been missed. It follows the profile's own interval: with a fixed half hour, a profile
 * checked hourly would be called stale half the time.
 *
 * The screens and the widget both ask here, so they cannot disagree.
 */
object Staleness {
    private const val INTERVALS = 2L

    /** For a profile that could not be looked up: the shortest interval there is. */
    val DEFAULT: Duration = after(PollInterval.FIFTEEN_MINUTES)

    fun after(interval: PollInterval): Duration = Duration.ofMinutes(INTERVALS * interval.minutes)

    /** Never fetched is not stale, it is empty: there is nothing to be old. */
    fun isStale(lastSuccess: Instant?, now: Instant, staleAfter: Duration): Boolean =
        lastSuccess != null && Duration.between(lastSuccess, now) > staleAfter
}
