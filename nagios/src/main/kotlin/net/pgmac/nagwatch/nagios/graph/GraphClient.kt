// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.model.ObjectRef
import okhttp3.HttpUrl

/** nagiosgraph on one Nagios instance. Obtain one from `NagiosClientFactory.createGraphs`. */
interface GraphClient {
    suspend fun fetch(request: GraphRequest): GraphAnswer

    /** nagiosgraph's own page for [ref], for a browser. Null when there is nowhere to point. */
    suspend fun pageUrl(ref: ObjectRef): String?
}

internal class OkHttpGraphClient(
    private val settings: ConnectionSettings,
    private val api: NagiosApi,
    private val cgiBase: suspend () -> NagiosResult<HttpUrl>,
) : GraphClient {
    override suspend fun fetch(request: GraphRequest): GraphAnswer = when (val base = base()) {
        is NagiosResult.Failure -> GraphAnswer.Unavailable(base.error)
        is NagiosResult.Success -> answer(request, api.image(base.value, GRAPH_CGI, request.params(), MAX_BYTES))
    }

    override suspend fun pageUrl(ref: ObjectRef): String? =
        (base() as? NagiosResult.Success)?.let { GraphRequest.pageUrl(it.value, ref) }

    /** The profile's own path if it gives one, and otherwise where Nagios' CGIs were found. */
    private suspend fun base(): NagiosResult<HttpUrl> = when {
        settings.graphPath.isBlank() -> cgiBase()

        else ->
            GraphPath.resolve(settings.baseUrl, settings.graphPath)?.let { NagiosResult.Success(it) }
                // The editor refuses such a path; one that got here anyway is not guessed at.
                ?: NagiosResult.Failure(NagiosError.NotNagios)
    }

    private fun answer(request: GraphRequest, image: NagiosApi.Image): GraphAnswer = when (image) {
        is NagiosApi.Image.Bytes ->
            GraphRules.judge(request.size, image.bytes)
                ?.let { GraphAnswer.Picture(image.bytes, it.width, it.height) }
                ?: GraphAnswer.NoGraph

        // Something is at that address and it is not nagiosgraph, which only ever sends PNGs.
        NagiosApi.Image.NotAnImage -> GraphAnswer.NotThere

        is NagiosApi.Image.Failed -> when (val error = image.error) {
            // No such page, or not for this user: asking about another service will not differ.
            NagiosError.NotNagios, NagiosError.Forbidden -> GraphAnswer.NotThere

            // The server drew more than this app asked for. Whatever it is, it is not shown.
            NagiosError.ResponseTooLarge -> GraphAnswer.NoGraph

            else -> GraphAnswer.Unavailable(error)
        }
    }

    private companion object {
        const val GRAPH_CGI = GraphRequest.GRAPH_CGI
        const val MAX_BYTES = GraphRules.MAX_BYTES
    }
}
