// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import kotlinx.serialization.json.JsonObject

internal object Fixtures {
    /** A sanitised capture from a real Nagios Core 4.5.9; see scripts/sanitise_fixtures.py. */
    fun text(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    fun json(name: String): JsonObject = TEST_JSON.parseToJsonElement(text(name)) as JsonObject

    /** What Apache sends when the CGI crashes. */
    const val SERVER_ERROR_HTML = "<html><head><title>500 Internal Server Error</title></head>" +
        "<body><h1>Internal Server Error</h1></body></html>"

    /** A page that is not Nagios at all, served with 200. */
    const val SOME_HTML = "<html><body><h1>It works!</h1></body></html>"
}
