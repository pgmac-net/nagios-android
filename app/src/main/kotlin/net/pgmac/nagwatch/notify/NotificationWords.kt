// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.Context
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.status.ActionProgress

/** The emoji and a space, or nothing at all, not a stray space, for the set that has none. */
internal fun String.before(title: String): String = if (isEmpty()) title else "$this $title"

internal fun ObjectRef.describe(context: Context): String =
    description?.let { context.getString(R.string.notify_service_on_host, it, hostName) } ?: hostName

/** A notification's title, "CRITICAL: %s" and so on: longer than the screens' short words. */
internal fun Severity.notifyWord(): Int = when (this) {
    Severity.HOST_DOWN -> R.string.notify_down
    Severity.HOST_UNREACHABLE -> R.string.notify_unreachable_host
    Severity.CRITICAL -> R.string.notify_critical
    Severity.WARNING -> R.string.notify_warning
    Severity.UNKNOWN -> R.string.notify_unknown
}

internal fun OutageCause.sentence(): Int = when (this) {
    OutageCause.UNREACHABLE -> R.string.notify_outage_unreachable
    OutageCause.CERTIFICATE -> R.string.notify_outage_certificate
    OutageCause.WRONG_ANSWER -> R.string.notify_outage_wrong_answer
    OutageCause.SERVER_ERROR -> R.string.notify_outage_server_error
    OutageCause.LOCAL_NETWORK -> R.string.notify_outage_local_network
    OutageCause.CREDENTIALS_REJECTED -> R.string.notify_outage_credentials_rejected
    OutageCause.ACCESS_REJECTED -> R.string.notify_outage_access_rejected
    OutageCause.NOT_ALLOWED -> R.string.notify_outage_not_allowed
    OutageCause.CREDENTIALS_UNREADABLE -> R.string.notify_outage_credentials_unreadable
    OutageCause.MISCONFIGURED -> R.string.notify_outage_misconfigured
}

/** What became of a button's command, in a line. The screens say the same things at more length. */
internal fun ActionProgress.sentence(context: Context): String = when (this) {
    is ActionProgress.Confirmed -> when (command) {
        is Command.Acknowledge -> context.getString(R.string.progress_confirmed_acknowledge)
        else -> context.getString(R.string.notify_acted_recheck)
    }

    is ActionProgress.NotConfirmed -> context.getString(R.string.notify_acted_not_confirmed)

    is ActionProgress.Refused -> reason.sentence(context, detail)

    is ActionProgress.NotSent -> context.getString(R.string.notify_acted_not_sent)

    else -> context.getString(R.string.progress_unknown)
}

/** Nagios' own words are used only where they say something the app's do not. */
internal fun Refusal.sentence(context: Context, detail: String): String = when (this) {
    Refusal.READ_ONLY -> context.getString(R.string.refused_read_only)

    Refusal.NOT_AUTHORISED -> context.getString(R.string.refused_not_authorised)

    Refusal.COMMANDS_DISABLED -> context.getString(R.string.refused_commands_disabled)

    Refusal.COULD_NOT_WRITE -> context.getString(R.string.refused_could_not_write)

    Refusal.AUTHENTICATION_DISABLED -> context.getString(R.string.refused_authentication_disabled)

    Refusal.INVALID -> when {
        detail.isBlank() -> context.getString(R.string.refused_invalid_no_detail)
        else -> context.getString(R.string.refused_invalid, detail)
    }
}
