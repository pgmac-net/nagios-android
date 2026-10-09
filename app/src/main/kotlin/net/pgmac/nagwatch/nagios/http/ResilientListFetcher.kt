// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import okhttp3.HttpUrl

/**
 * Fetches a whole `hostlist` / `servicelist` with details, surviving the case
 * where Nagios cannot serialise one record.
 *
 * Measured against Nagios Core 4.5.9: a single service whose plugin output
 * contains non-ASCII text makes `statusjson.cgi` answer HTTP 500 for any
 * `details=true` window that includes it. The same record without details is
 * fine, and `start`/`count` windows slice the list consistently with and
 * without details. One broken check must not blind a monitoring app, so:
 *
 * 1. Page through the list with details. Healthy servers cost nothing extra.
 * 2. When a window answers 5xx, halve it until the failing record is alone.
 * 3. Re-fetch that one record without details: name and state only. It is
 *    returned as a *degraded* record.
 *
 * The extra work is bounded. A server that is broken in general, rather than
 * tripping on one or two records, exhausts the budget and the original error
 * is reported instead of being hammered.
 *
 * So is the list itself: how many pages there are is the server's say, so a
 * list that never ends is cut off at [maxRecords] and reported as too large.
 */
internal class ResilientListFetcher(
    private val api: NagiosApi,
    private val pageSize: Int = PAGE_SIZE,
    private val maxExtraRequests: Int = MAX_EXTRA_REQUESTS,
    private val maxDegraded: Int = MAX_DEGRADED,
    private val maxRecords: Int = MAX_RECORDS,
) {
    /**
     * @param filters query parameters that select the list (e.g. `servicestatus`);
     *   paging and detail parameters are added here.
     * @param parse turns one response into records, in list order. It must
     *   handle both the detailed and the state-only shape.
     */
    suspend fun <T> fetchAll(
        cgiBase: HttpUrl,
        query: String,
        filters: List<Pair<String, String>>,
        parse: (JsonObject) -> List<T>,
    ): NagiosResult<List<T>> {
        val run = Run(cgiBase, query, filters, parse)
        val records = mutableListOf<T>()
        var start = 0
        while (true) {
            when (val page = run.window(start, pageSize, isRetry = false)) {
                is NagiosResult.Failure -> return page

                is NagiosResult.Success -> {
                    records += page.value
                    if (page.value.size < pageSize) return NagiosResult.Success(records)
                    // The server decides when the list ends. One that never sends a short
                    // page (ignoring `start`, say) must not be followed forever.
                    if (records.size >= maxRecords) return NagiosResult.Failure(NagiosError.ResponseTooLarge)
                    start += pageSize
                }
            }
        }
    }

    private inner class Run<T>(
        private val cgiBase: HttpUrl,
        private val query: String,
        private val filters: List<Pair<String, String>>,
        private val parse: (JsonObject) -> List<T>,
    ) {
        private var extraRequests = 0
        private var degraded = 0

        /** Records `start until start + count`, isolating any that Nagios cannot serialise. */
        suspend fun window(start: Int, count: Int, isRetry: Boolean): NagiosResult<List<T>> {
            if (isRetry && !spend()) return OVER_BUDGET
            val detailed = request(start, count, details = true)
            val error = (detailed as? NagiosResult.Failure)?.error
            if (error !is NagiosError.Http || !error.isServerError) return detailed

            // Before searching, confirm the same window works without details. If it
            // does not, the server is failing in general and a search would only add load.
            if (!isRetry && (!spend() || request(start, count, details = false) is NagiosResult.Failure)) {
                return detailed
            }

            return when {
                count > 1 -> split(start, count, error)
                ++degraded > maxDegraded || !spend() -> NagiosResult.Failure(error)
                else -> request(start, 1, details = false)
            }
        }

        private suspend fun split(start: Int, count: Int, original: NagiosError): NagiosResult<List<T>> {
            val half = count / 2
            val left = window(start, half, isRetry = true)
            val right = if (left is NagiosResult.Success) window(start + half, count - half, isRetry = true) else left
            return when {
                left is NagiosResult.Success && right is NagiosResult.Success ->
                    NagiosResult.Success(left.value + right.value)

                left === OVER_BUDGET || right === OVER_BUDGET -> NagiosResult.Failure(original)

                else -> listOf(left, right).first { it is NagiosResult.Failure }
            }
        }

        private fun spend(): Boolean = ++extraRequests <= maxExtraRequests

        private suspend fun request(start: Int, count: Int, details: Boolean): NagiosResult<List<T>> {
            val params = buildList {
                add("query" to query)
                add("formatoptions" to "enumerate")
                if (details) add("details" to "true")
                addAll(filters)
                add("start" to start.toString())
                add("count" to count.toString())
            }
            return when (val response = api.query(cgiBase, STATUS_CGI, params)) {
                is NagiosResult.Success -> NagiosResult.Success(parse(response.value))
                is NagiosResult.Failure -> response
            }
        }
    }

    companion object {
        const val STATUS_CGI = "statusjson.cgi"
        const val PAGE_SIZE = 100

        /** Isolating one record in a page of 100 takes about 15 requests; this allows two or three. */
        const val MAX_EXTRA_REQUESTS = 40
        const val MAX_DEGRADED = 5

        /** Far above any real install's host or problem count; a ceiling, not a target. */
        const val MAX_RECORDS = 50_000

        /** Internal marker so a spent budget surfaces as the error that started the search. */
        private val OVER_BUDGET = NagiosResult.Failure(NagiosError.Http(code = 0))
    }
}
