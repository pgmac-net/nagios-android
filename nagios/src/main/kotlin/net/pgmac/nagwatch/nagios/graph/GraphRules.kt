// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

import net.pgmac.nagwatch.nagios.NagiosError

/** What asking for a graph came to. */
sealed interface GraphAnswer {
    /** A graph. [png] has been checked to be a PNG of [width] by [height], and no more than that. */
    class Picture(val png: ByteArray, val width: Int, val height: Int) : GraphAnswer

    /** nagiosgraph answered and has nothing to draw for this service. Not an error. */
    data object NoGraph : GraphAnswer

    /** There is no nagiosgraph where this profile looks for it. True of every service at once. */
    data object NotThere : GraphAnswer

    /** It could not be asked, or not be answered, this time. Says nothing about next time. */
    data class Unavailable(val error: NagiosError) : GraphAnswer
}

/**
 * Whether what `showgraph.cgi` sent is a graph.
 *
 * It cannot be told from the status or the content type. With nothing to draw, nagiosgraph
 * answers 200 and `image/png` all the same: a picture of the error message 23 pixels high, a
 * blank one five pixels square, or nothing at all after the headers (measured on 1.5.2; the
 * captures are in the tests). So it is told from the picture.
 *
 * The rule needs no number of its own. The picture of a graph is always larger than the plot
 * asked for, because the plot is inside it; and this app never asks for a plot shorter than an
 * error message of several lines is tall (each line is 15 pixels; the shortest plot asked for
 * is 120).
 */
object GraphRules {
    /**
     * The most that is read. A graph is a few tens of kilobytes; the server will draw any size
     * it is asked for, and this app asks only for its own.
     */
    const val MAX_BYTES = 2L * 1024 * 1024

    /**
     * How much larger than its plot a picture may be. The width grows by an axis (81, measured); the height
     * by a title and a legend with a line for each thing plotted, which on a busy service is
     * many. Past this it is not something this app asked for, and it is not decoded.
     */
    const val MAX_EXTRA_WIDTH = 200
    const val MAX_EXTRA_HEIGHT = 2_000

    /** The picture's size if [bytes] is a graph drawn for [asked], or null. */
    fun judge(asked: GraphSize, bytes: ByteArray): PngHeader? = PngHeader.read(bytes)?.takeIf { header ->
        header.width in asked.width..asked.width + MAX_EXTRA_WIDTH &&
            header.height in asked.height..asked.height + MAX_EXTRA_HEIGHT
    }
}
