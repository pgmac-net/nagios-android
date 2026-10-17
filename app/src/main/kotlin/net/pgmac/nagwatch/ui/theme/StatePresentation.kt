// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.Severity

/*
 * The one place a state becomes a colour and a short word. The screens, the widget and the
 * notifications all draw from here, so they cannot drift apart. An unreachable host is drawn as
 * unknown: it could not be checked, which is not the same as down (docs/adr/0014).
 */

fun StateColors.of(severity: Severity): Color = when (severity) {
    Severity.HOST_DOWN, Severity.CRITICAL -> critical
    Severity.WARNING -> warning
    Severity.HOST_UNREACHABLE, Severity.UNKNOWN -> unknown
}

fun StateColors.of(state: HostState): Color = when (state) {
    HostState.UP -> ok
    HostState.DOWN -> critical
    HostState.UNREACHABLE -> unknown
    HostState.PENDING -> pending
}

fun StateColors.of(state: ServiceState): Color = when (state) {
    ServiceState.OK -> ok
    ServiceState.WARNING -> warning
    ServiceState.CRITICAL -> critical
    ServiceState.UNKNOWN -> unknown
    ServiceState.PENDING -> pending
}

/** The word on a badge or chip: CRIT, WARN, DOWN and so on. */
@StringRes
fun Severity.shortWord(): Int = when (this) {
    Severity.HOST_DOWN -> R.string.severity_down
    Severity.HOST_UNREACHABLE -> R.string.severity_unreachable
    Severity.CRITICAL -> R.string.severity_critical
    Severity.WARNING -> R.string.severity_warning
    Severity.UNKNOWN -> R.string.severity_unknown
}

@StringRes
fun HostState.shortWord(): Int = when (this) {
    HostState.UP -> R.string.state_up
    HostState.DOWN -> R.string.severity_down
    HostState.UNREACHABLE -> R.string.state_unreachable_short
    HostState.PENDING -> R.string.state_pending
}

@StringRes
fun ServiceState.shortWord(): Int = when (this) {
    ServiceState.OK -> R.string.state_ok
    ServiceState.WARNING -> R.string.severity_warning
    ServiceState.CRITICAL -> R.string.severity_critical
    ServiceState.UNKNOWN -> R.string.severity_unknown
    ServiceState.PENDING -> R.string.state_pending
}
