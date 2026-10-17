// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.net.URLEncoder
import net.pgmac.nagwatch.nagios.model.ObjectConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A notes or action address, as the detail screen offers it (pgmac-net/nagios-android#82). */
sealed interface NagiosLink {
    /** Safe to hand to the browser. */
    data class Open(val url: String) : NagiosLink

    /** Shown as words and never opened: not a web address, or a macro the app does not fill in. */
    data class Text(val text: String) : NagiosLink
}

/**
 * Turns what a host's or service's definition says (`notes_url`, `action_url`, `notes`) into
 * what the app shows. The words come from the server and an address from it is opened in
 * another app, so the rule is narrow:
 *
 * - The standard macros are filled in as Nagios' own web pages do: `$HOSTNAME$`, `$HOSTALIAS$`,
 *   `$HOSTADDRESS$`, `$SERVICEDESC$` (a service's only), each URL-encoded in an address.
 * - Anything still shaped like a macro afterwards is left as words, since the address it would
 *   make is not known.
 * - Only `http` and `https` are opened. `intent:`, `javascript:`, `file:` and the rest are words.
 * - A relative address is read against the profile's Nagios, as its own pages read it.
 * - A user name or password in the address is removed, and none of the profile's is ever added:
 *   the browser goes there as anyone would.
 */
object NagiosLinks {
    private val MACRO = Regex("""\$[A-Z0-9_]+\$""")

    fun link(raw: String, config: ObjectConfig, base: HttpUrl?): NagiosLink? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val filled = expand(text, config, encode = true)
        val url = filled.takeUnless { MACRO.containsMatchIn(it) }?.let { resolve(it, base) }
            ?: return NagiosLink.Text(text)
        return NagiosLink.Open(url.newBuilder().username("").password("").build().toString())
    }

    /** Notes are words to read: their macros are filled in plain. */
    fun notes(raw: String, config: ObjectConfig): String = expand(raw.trim(), config, encode = false)

    private fun resolve(text: String, base: HttpUrl?): HttpUrl? =
        text.toHttpUrlOrNull() ?: if (text.contains(':') && !text.startsWith("/")) null else base?.resolve(text)

    private fun expand(text: String, config: ObjectConfig, encode: Boolean): String {
        val values = buildMap {
            put("\$HOSTNAME\$", config.hostName)
            put("\$HOSTALIAS\$", config.hostAlias)
            put("\$HOSTADDRESS\$", config.hostAddress)
            config.description?.let { put("\$SERVICEDESC\$", it) }
        }
        return MACRO.replace(text) { match ->
            val value = values[match.value] ?: return@replace match.value
            if (encode) URLEncoder.encode(value, "UTF-8") else value
        }
    }
}
