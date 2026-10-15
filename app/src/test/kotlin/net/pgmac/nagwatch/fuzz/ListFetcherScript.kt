// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.http.ResilientListFetcher
import net.pgmac.nagwatch.nagios.http.StatusParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A Nagios server that answers from a script, so that the fuzzer can ask "whatever the server
 * says, does the fetcher keep its limits?" (pgmac-net/nagios-android#32).
 *
 * The input is carved up by position, with no structure to get wrong, so a mutation of one
 * byte is always a valid script:
 *
 * - **Four bytes of limits.** Page size 1 to 16; the record ceiling 0 to 255; the extra-request
 *   budget 0 to 40; and in the fourth, the degraded-record cap (low three bits, 0 to 5) and
 *   whether details are asked for (bit 3 clear: yes). Small, so that the edges are a few
 *   replies away and not 500.
 * - **Then replies, two bytes each**: a kind and a number. The script starts again from its
 *   first reply when it runs out, so a short input stands for a server that never stops.
 *   No replies at all is one empty page.
 *
 * | kind (mod 7) | the reply |
 * |---|---|
 * | 0 | a page of the number of records, but no more than were asked for |
 * | 1 | a page of the number of records, whatever was asked (a server that ignores `count`) |
 * | 2 | HTTP 500 |
 * | 3 | HTTP 4xx (the number, mod 100, above 400) |
 * | 4 | an I/O error |
 * | 5 | a 200 that is not Nagios' JSON |
 * | 6 | what real Nagios does with a poisoned record: 500 with details, a page without |
 *
 * The properties are the contract in the class's own documentation: a result or a
 * `NagiosError`, never an exception; no more requests than a page each and the budget; no list
 * past the ceiling; no more degraded records than allowed; and the marker the fetcher uses
 * internally for a spent budget is never what the caller is given.
 */
internal object ListFetcherScript {
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_TYPE = "application/json".toMediaType()
    private val BASE = "http://nagios.example.org/cgi-bin/".toHttpUrl()

    private const val HEADER_BYTES = 4
    private const val PAGE_SIZES = 16
    private const val RECORD_CEILINGS = 256
    private const val BUDGETS = 41
    private const val DEGRADED_CAPS = 6
    private const val DEGRADED_BITS = 7
    private const val NO_DETAILS_BIT = 8
    private const val KINDS = 7
    private const val CLIENT_ERROR = 400
    private const val CLIENT_ERRORS = 100
    private const val BYTE = 0xFF
    private const val WAIT_MS = 5_000L

    fun run(data: ByteArray) {
        fun at(index: Int) = if (index < data.size) data[index].toInt() and BYTE else 0
        val pageSize = 1 + at(0) % PAGE_SIZES
        val maxRecords = at(1) % RECORD_CEILINGS
        val maxExtra = at(2) % BUDGETS
        val maxDegraded = (at(3) and DEGRADED_BITS) % DEGRADED_CAPS
        val details = at(3) and NO_DETAILS_BIT == 0
        val replies = (HEADER_BYTES until data.size - 1 step 2).map { Reply(at(it) % KINDS, at(it + 1)) }
            .ifEmpty { listOf(Reply(kind = 0, number = 0)) }

        val server = Server(replies)
        val client = OkHttpClient.Builder().addInterceptor(server).build()
        val fetcher = ResilientListFetcher(
            NagiosApi(client, json, Dispatchers.Unconfined),
            pageSize = pageSize,
            maxExtraRequests = maxExtra,
            maxDegraded = maxDegraded,
            maxRecords = maxRecords,
        )

        val result = runBlocking {
            withTimeout(WAIT_MS) {
                fetcher.fetchAll(BASE, "servicelist", emptyList(), details, StatusParser::services)
            }
        }

        val pages = maxRecords / pageSize + 1
        check(server.requests <= pages + maxExtra) {
            "${server.requests} requests, where $pages pages and $maxExtra extra are allowed"
        }
        when (result) {
            is NagiosResult.Success -> {
                check(result.value.size <= maxRecords) { "${result.value.size} records past a ceiling of $maxRecords" }
                val degraded = result.value.count { !it.check.detailsAvailable }
                // Without details nothing is degraded: every record is the short form.
                check(!details || degraded <= maxDegraded) { "$degraded degraded records, $maxDegraded allowed" }
            }

            is NagiosResult.Failure -> {
                val error = result.error
                check(!(error is NagiosError.Http && error.code == 0)) { "the fetcher's own marker was returned" }
            }
        }
    }

    private class Reply(val kind: Int, val number: Int)

    private class Server(private val replies: List<Reply>) : Interceptor {
        var requests = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val reply = replies[requests % replies.size]
            requests++
            val asked = url.queryParameter("count")?.toIntOrNull() ?: 0
            val start = url.queryParameter("start")?.toIntOrNull() ?: 0
            val detailed = url.queryParameter("details") == "true"
            val request = chain.request()
            return when (reply.kind) {
                0 -> page(request, start, minOf(reply.number, asked), detailed)

                1 -> page(request, start, reply.number, detailed)

                2 -> answer(request, 500, "<html>Internal Server Error</html>")

                3 -> answer(request, CLIENT_ERROR + reply.number % CLIENT_ERRORS, "<html>no</html>")

                4 -> throw IOException("the script said so")

                5 -> answer(request, 200, "<html>not Nagios</html>")

                else ->
                    if (detailed) {
                        answer(request, 500, "<html>Internal Server Error</html>")
                    } else {
                        page(request, start, minOf(reply.number, asked), detailed = false)
                    }
            }
        }

        private fun page(request: okhttp3.Request, start: Int, count: Int, detailed: Boolean): Response {
            val services = (start until start + count).joinToString(",") { index ->
                val entry = if (detailed) """{"status": "ok", "current_attempt": 1, "max_attempts": 3}""" else "\"ok\""
                "\"svc$index\": $entry"
            }
            val body = """{"result": {"type_code": 0, "type_text": "Success"}, """ +
                """"data": {"servicelist": {"web01": {$services}}}}"""
            return answer(request, 200, body)
        }

        private fun answer(request: okhttp3.Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(body.toResponseBody(JSON_TYPE))
            .build()
    }
}
