// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

/**
 * What Nagwatch has actually been run against, and what it only expects to
 * work with. The JSON CGIs it reads date from Nagios Core 4.0.7; everything
 * here was built and tested against 4.5. Nothing is refused on the strength
 * of a version number: an older server gets a note, and each part of the app
 * that cannot get its data says so on its own.
 */
object NagiosVersion {
    /** Below this, say plainly that the version is untested. */
    private const val TESTED_MAJOR = 4
    private const val TESTED_MINOR = 4

    /**
     * True when [version] is recognisably older than the oldest release line
     * Nagwatch is confident about. A version that cannot be read gets no note:
     * a warning based on a guess is worse than none.
     */
    fun isOlderThanTested(version: String): Boolean {
        val parts = version.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
        return major < TESTED_MAJOR || (major == TESTED_MAJOR && minor < TESTED_MINOR)
    }
}
