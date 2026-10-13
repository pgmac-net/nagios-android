// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import okhttp3.HttpUrl

/**
 * Where nagiosgraph's CGIs are, when they are not where Nagios' own are.
 *
 * A path and only a path, on the server the profile already names. Not a URL: the profile's
 * credentials go to one origin (see `ConnectionInterceptor`), and a setting that could name
 * another would either leak them or be refused at the moment of use, which nobody could make
 * sense of. Blank means "where Nagios' CGIs are", which the app has already found.
 */
object GraphPath {
    const val MAX_LENGTH = 200

    /** Unreserved characters only: nothing that needs encoding, and so nothing that can be encoded twice. */
    private val SEGMENT = Regex("^[A-Za-z0-9._~-]+$")
    private val DOTS = setOf(".", "..")

    /** Blank is valid. */
    fun isValid(text: String): Boolean = normalise(text) != null

    /**
     * The path as it is stored and used: empty, or starting and ending with a slash. Null when
     * [text] is not a path this app will use.
     */
    fun normalise(text: String): String? {
        val trimmed = text.trim()
        val segments = trimmed.trim('/').split('/')
        val stored = segments.joinToString(separator = "/", prefix = "/", postfix = "/")
        return when {
            trimmed.isEmpty() -> ""

            // Not "nagiosgraph/cgi-bin", which would be relative to who knows what.
            !trimmed.startsWith('/') -> null

            // Not "//other.example/": to a browser, and to a careless join, that is a host.
            trimmed.startsWith("//") -> null

            segments.any { !SEGMENT.matches(it) || it in DOTS } -> null

            // The length of what is stored, not of what was typed: a path accepted today must be
            // accepted when it is read back tomorrow with its last slash added.
            stored.length > MAX_LENGTH -> null

            else -> stored
        }
    }

    /**
     * The CGI directory at [path] on [baseUrl]'s own scheme, host and port, and nothing else of
     * it: not its query, and not a user and password typed into it. Credentials travel in a
     * header the interceptor adds, never in a URL. Null for blank or invalid.
     */
    fun resolve(baseUrl: HttpUrl, path: String): HttpUrl? = normalise(path)?.takeIf { it.isNotEmpty() }?.let {
        baseUrl.newBuilder().username("").password("").encodedPath(it).query(null).fragment(null).build()
    }
}
