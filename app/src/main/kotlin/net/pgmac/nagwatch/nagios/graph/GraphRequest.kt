// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import net.pgmac.nagwatch.nagios.model.ObjectRef
import okhttp3.HttpUrl

/** How far back a graph goes. nagiosgraph draws any of these whatever its own pages offer. */
enum class GraphPeriod(val param: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
}

/**
 * The size of the plot asked for: the area the lines are drawn in. The picture that comes back
 * is larger, by the axes, the title and a legend that grows with the number of things plotted.
 *
 * In dp, not in the screen's own pixels. rrdtool draws its text at a fixed size in pixels, so a
 * picture asked for at a phone's full resolution has labels nobody can read. Asked for small and
 * scaled up, it is a little soft and can be read.
 */
data class GraphSize(val width: Int, val height: Int) {
    val geom: String get() = "${width}x$height"

    companion object {
        /** For the viewer that has the whole screen, on its side. */
        val FULL = GraphSize(width = 700, height = 240)

        private const val INLINE_HEIGHT = 120
        private const val MIN_WIDTH = 240
        private const val MAX_WIDTH = 660
        private const val STEP = 60

        /** What rrdtool adds to the plot's width for the axis and its labels (measured: 81). */
        private const val AXIS_WIDTH = 81

        /**
         * A plot whose picture fits in [availableDp], in steps: a few sizes, so that a window
         * dragged a little wider does not mean another request and another picture to keep.
         */
        fun inline(availableDp: Int): GraphSize {
            val plot = (availableDp - AXIS_WIDTH).coerceIn(MIN_WIDTH, MAX_WIDTH)
            return GraphSize(width = plot - plot % STEP, height = INLINE_HEIGHT)
        }
    }
}

/**
 * One picture of one service's history.
 *
 * Only a service: nagiosgraph files a host's own check under whatever name the administrator
 * gave it, so there is nothing to ask for that would be right on more than one server.
 */
data class GraphRequest(val hostName: String, val service: String, val period: GraphPeriod, val size: GraphSize) {
    /** Everything that is sent. Never `rrdopts`, which is passed to rrdtool as it stands. */
    fun params(): List<Pair<String, String>> =
        listOf("host" to hostName, "service" to service, "period" to period.param, "geom" to size.geom)

    companion object {
        const val GRAPH_CGI = "showgraph.cgi"
        const val PAGE_CGI = "show.cgi"

        /** Null for a host. */
        fun of(ref: ObjectRef, period: GraphPeriod, size: GraphSize): GraphRequest? =
            ref.description?.let { GraphRequest(ref.hostName, it, period, size) }

        /** nagiosgraph's own page for a service, for a browser. Null for a host. */
        fun pageUrl(base: HttpUrl, ref: ObjectRef): String? = ref.description?.let { service ->
            base.newBuilder()
                .addPathSegment(PAGE_CGI)
                .addQueryParameter("host", ref.hostName)
                .addQueryParameter("service", service)
                .build()
                .toString()
        }
    }
}
