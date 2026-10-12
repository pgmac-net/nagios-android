// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import java.time.Duration
import net.pgmac.nagwatch.profile.PollInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One rule for "too old to pass off as current", for the screens and the widget alike. */
class StalenessTest {
    @Test
    fun `it is twice the profile's interval`() {
        assertEquals(Duration.ofMinutes(30), Staleness.after(PollInterval.FIFTEEN_MINUTES))
        assertEquals(Duration.ofMinutes(60), Staleness.after(PollInterval.THIRTY_MINUTES))
        assertEquals(Duration.ofMinutes(120), Staleness.after(PollInterval.ONE_HOUR))
        assertEquals("for a profile that cannot be looked up, the shortest", Duration.ofMinutes(30), Staleness.DEFAULT)
    }

    @Test
    fun `exactly at the limit is not stale, and a second past it is`() {
        val limit = Staleness.after(PollInterval.FIFTEEN_MINUTES)

        assertFalse(Staleness.isStale(T0, T0 + limit, limit))
        assertTrue(Staleness.isStale(T0, T0 + limit + Duration.ofSeconds(1), limit))
        assertFalse(
            "one interval old is what a healthy poll looks like",
            Staleness.isStale(T0, T0 + limit.dividedBy(2), limit),
        )
    }

    @Test
    fun `never fetched is not stale, it is empty`() {
        assertFalse(Staleness.isStale(null, T0 + Duration.ofDays(30), Staleness.DEFAULT))
    }

    @Test
    fun `a clock that went backwards does not make fresh data stale`() {
        assertFalse(Staleness.isStale(T0, T0 - Duration.ofHours(5), Staleness.DEFAULT))
    }
}
