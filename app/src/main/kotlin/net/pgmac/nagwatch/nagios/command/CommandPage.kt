// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

/**
 * Reads the HTML that Nagios' `cmd.cgi` answers with. Everything the app knows about
 * that HTML is in this file.
 *
 * `cmd.cgi` has no JSON form. It answers a request with a page, and the only way to
 * know what happened is to recognise the page. This is the most fragile thing in the
 * app, so it is kept in one place, tested against pages captured from a real Nagios
 * (`app/src/test/resources/commandpages`), and fuzzed. If a future Nagios rewords a
 * message, the fix is here and nowhere else.
 *
 * The rule that matters most: **a page that is not recognised is never success.** Only
 * Nagios' own "successfully submitted" sentence, inside its own `infoMessage` block,
 * counts. Everything else is a refusal with a known reason, or [Result.Unrecognised].
 *
 * The page is untrusted input from a server. Nothing here recurses, nothing is evaluated,
 * and what is kept of Nagios' own words is cut to [MAX_DETAIL] characters.
 */
internal object CommandPage {
    sealed interface Result {
        data object Accepted : Result

        data class Refused(val reason: Refusal, val detail: String = "") : Result

        /** Neither a success nor any failure Nagios is known to write. */
        data object Unrecognised : Result
    }

    /** What the page for a committed command says happened. */
    fun result(html: String): Result {
        val info = message(html, INFO_CLASS)
        if (info != null && SUBMITTED in info) return Result.Accepted
        val error = message(html, ERROR_CLASS) ?: return Result.Unrecognised
        val reason = KNOWN_REFUSALS.firstOrNull { (words, _) -> words in error }?.second
        return when (reason) {
            null -> Result.Refused(Refusal.INVALID, error)
            else -> Result.Refused(reason)
        }
    }

    /**
     * Whether the page says this user is read-only. Nagios says so on a plain request for
     * a command form, before anything is committed, which is how the app can know without
     * trying to change anything.
     */
    fun isReadOnly(html: String): Boolean = message(html, ERROR_CLASS)?.contains(READ_ONLY_WORDS) == true

    /** Whether the page is a command form: something that could be filled in and committed. */
    fun isForm(html: String): Boolean = field(html, COMMIT_FIELD) != null

    /**
     * The value an `<INPUT>` on the page was pre-filled with, or null if there is no input
     * of that name. Nagios writes attributes in single quotes; double quotes are read too.
     */
    fun field(html: String, name: String): String? {
        var at = html.indexOf(INPUT_TAG, ignoreCase = true)
        while (at >= 0) {
            val close = html.indexOf('>', at)
            if (close < 0) break
            val tag = html.substring(at, close)
            if (attribute(tag, "name") == name) return attribute(tag, "value").orEmpty()
            at = html.indexOf(INPUT_TAG, close, ignoreCase = true)
        }
        return null
    }

    private fun attribute(tag: String, name: String): String? {
        var at = tag.indexOf("$name=", ignoreCase = true)
        while (at >= 0) {
            // A whole attribute name, not the tail of another one.
            val standsAlone = at > 0 && tag[at - 1].isWhitespace()
            val quote = tag.getOrNull(at + name.length + 1)
            if (standsAlone && (quote == '\'' || quote == '"')) {
                val start = at + name.length + 2
                val end = tag.indexOf(quote, start)
                return if (end < 0) null else unescape(tag.substring(start, end))
            }
            at = tag.indexOf("$name=", at + 1, ignoreCase = true)
        }
        return null
    }

    /** The text of the first `<DIV CLASS='...'>` of that class, as plain words. */
    private fun message(html: String, cssClass: String): String? {
        val open = html.indexOf("class='$cssClass'", ignoreCase = true)
            .takeIf { it >= 0 } ?: html.indexOf("class=\"$cssClass\"", ignoreCase = true)
        if (open < 0) return null
        val start = html.indexOf('>', open)
        if (start < 0) return null
        val end = html.indexOf("</div", start, ignoreCase = true).takeIf { it >= 0 } ?: html.length
        return plainText(html.substring(start + 1, minOf(end, start + 1 + MAX_SCAN)))
    }

    /** Tags out, entities decoded, whitespace collapsed, and no longer than [MAX_DETAIL]. */
    private fun plainText(fragment: String): String {
        val text = StringBuilder()
        var inTag = false
        for (c in fragment) {
            when {
                c == '<' -> {
                    inTag = true
                    text.append(' ')
                }

                c == '>' && inTag -> inTag = false

                !inTag -> text.append(c)
            }
        }
        return unescape(text.toString()).split(WHITESPACE).filter(String::isNotEmpty).joinToString(" ").take(MAX_DETAIL)
    }

    private fun unescape(text: String): String = ENTITIES.entries.fold(text) { acc, (entity, char) ->
        acc.replace(entity, char)
    }

    private const val INPUT_TAG = "<input"
    private const val COMMIT_FIELD = "cmd_mod"
    private const val INFO_CLASS = "infoMessage"
    private const val ERROR_CLASS = "errorMessage"

    /** How much of a message block is looked at. Nagios' own are a few hundred characters. */
    private const val MAX_SCAN = 4_000

    /** How much of Nagios' own wording is passed on to be shown. */
    const val MAX_DETAIL = 300

    private val WHITESPACE = Regex("""\s+""")

    // `&amp;` last, so that `&amp;lt;` becomes `&lt;` and not `<`.
    private val ENTITIES = linkedMapOf(
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " ",
        "&amp;" to "&",
    )

    // The sentences are Nagios' own, from cgi/cmd.c. Each is matched on a part distinctive
    // enough not to appear in another, and short enough to survive small rewordings.
    private const val SUBMITTED = "successfully submitted"
    private const val READ_ONLY_WORDS = "do not have permission to submit"
    private val KNOWN_REFUSALS = listOf(
        READ_ONLY_WORDS to Refusal.READ_ONLY,
        "not authorized to commit" to Refusal.NOT_AUTHORISED,
        "not checking for external commands" to Refusal.COMMANDS_DISABLED,
        "error occurred while attempting to commit" to Refusal.COULD_NOT_WRITE,
        "Sorry Dave" to Refusal.AUTHENTICATION_DISABLED,
    )
}
