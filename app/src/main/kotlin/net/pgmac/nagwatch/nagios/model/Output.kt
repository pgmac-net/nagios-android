// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.model

/** How much of a plugin's first line is shown where there is room for one line only. */
const val OUTPUT_LINE_MAX = 300

/**
 * The first line of what a plugin printed, trimmed and of a bounded length.
 *
 * Plugin output is whatever a script wrote: any length, any number of lines. A notification and
 * a widget row each have room for one line, and both take it from here so that they agree.
 */
fun CheckStatus.firstOutputLine(max: Int = OUTPUT_LINE_MAX): String =
    pluginOutput.lineSequence().firstOrNull().orEmpty().trim().take(max)
