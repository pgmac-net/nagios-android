// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosError.Unreachable.Reason

/**
 * What to tell the user for each failure. Every class of error gets its own
 * sentence, saying what went wrong and where possible what to do about it
 * (design section 4): never a generic "connection failed".
 */
fun NagiosError.toUiText(): UiText = when (this) {
    NagiosError.AccessRejected -> UiText(R.string.error_access_rejected)
    NagiosError.BadCredentials -> UiText(R.string.error_bad_credentials)
    NagiosError.Forbidden -> UiText(R.string.error_forbidden)
    is NagiosError.Certificate -> UiText(R.string.error_certificate, listOf(detail))
    is NagiosError.Unreachable -> unreachableText()
    NagiosError.NotNagios -> UiText(R.string.error_not_nagios)
    is NagiosError.Api -> UiText(R.string.error_nagios_api, listOf(message.ifBlank { type }))
    NagiosError.CleartextRefused -> UiText(R.string.error_cleartext_refused)
    NagiosError.AccessOverCleartext -> UiText(R.string.error_access_over_cleartext)
    is NagiosError.Redirected -> UiText(R.string.error_redirected, listOf(location))
    NagiosError.ResponseTooLarge -> UiText(R.string.error_response_too_large)
    is NagiosError.Http -> UiText(R.string.error_http, listOf(code))
}

private fun NagiosError.Unreachable.unreachableText(): UiText = when (reason) {
    Reason.DNS -> UiText(R.string.error_unreachable_dns)
    Reason.REFUSED -> UiText(R.string.error_unreachable_refused)
    Reason.TIMEOUT -> UiText(R.string.error_unreachable_timeout)
    Reason.OTHER -> UiText(R.string.error_unreachable_other, listOf(detail))
}
