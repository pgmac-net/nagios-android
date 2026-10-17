// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

/**
 * Whether a timeout reaching a host is probably Android's local network protection.
 *
 * A seam so the Nagios client does not know about Android: it asks, after a connection
 * has timed out, and says so when the answer is yes.
 */
fun interface LocalNetworkGuard {
    /** May look the name up, so it must not be called from the main thread. */
    fun likelyBlocking(host: String): Boolean

    companion object {
        /** For where the protection does not apply, and for tests that are about something else. */
        val Never = LocalNetworkGuard { false }
    }
}
