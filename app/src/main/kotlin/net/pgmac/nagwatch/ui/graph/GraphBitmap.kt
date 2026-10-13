// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pgmac.nagwatch.status.GraphState

/**
 * A graph on its way to being drawn. [of] is the graph [bitmap] was decoded from: a picture of
 * another period is kept on screen while the next one is decoded, and this says which is which.
 */
class DecodedGraph(val of: GraphState.Shown?, val bitmap: ImageBitmap?) {
    /** The decoder was asked about [graph] and said no. There is nothing to wait for. */
    fun failed(graph: GraphState.Shown?): Boolean = graph != null && of === graph && bitmap == null
}

/** Turns a graph's bytes into something that can be drawn. */
object GraphBitmap {
    /**
     * Null if the decoder does not find the picture the header promised.
     *
     * The size was checked before the bytes were kept (`GraphRules`). It is checked again here,
     * by asking the decoder for the size alone before letting it allocate anything: the header
     * reader and the decoder are two readers of the same bytes, and the bound only holds if
     * they agree.
     */
    fun decode(graph: GraphState.Shown): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(graph.png, 0, graph.png.size, bounds)
        val agrees = bounds.outWidth == graph.width && bounds.outHeight == graph.height
        return if (agrees) BitmapFactory.decodeByteArray(graph.png, 0, graph.png.size)?.asImageBitmap() else null
    }
}

/**
 * The decoded picture, once it is: decoding is kept off the thread that draws. Until the next
 * one is ready, the last one stays.
 */
@Composable
fun rememberGraphBitmap(
    graph: GraphState.Shown?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): State<DecodedGraph> = produceState(initialValue = DecodedGraph(null, null), graph) {
    value = if (graph == null) {
        DecodedGraph(null, null)
    } else {
        DecodedGraph(graph, withContext(dispatcher) { GraphBitmap.decode(graph) })
    }
}
