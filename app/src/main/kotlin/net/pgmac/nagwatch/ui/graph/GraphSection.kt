// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.status.GraphState

/** Names for the parts of a graph on screen, so that tests can find them. */
object GraphTags {
    const val SECTION = "graph_section"
    const val PICTURE = "graph_picture"
    const val LOADING = "graph_loading"
    const val BROWSER = "graph_browser"
    const val VIEWER = "graph_viewer"
    const val VIEWER_PICTURE = "graph_viewer_picture"
    const val VIEWER_NONE = "graph_viewer_none"
    const val VIEWER_BACK = "graph_viewer_back"
    const val VIEWER_TITLE = "graph_viewer_title"

    fun period(period: GraphPeriod) = "graph_period_${period.name}"
}

/** What the graph on a detail screen can ask for. */
interface GraphActions {
    /** There is this much room: ask for a graph that fits it. */
    fun show(size: GraphSize)

    fun choose(period: GraphPeriod)

    /** Give the graph the whole screen. */
    fun open()

    /** Hands nagiosgraph's own page to the browser. */
    fun openInBrowser(url: String)
}

/**
 * A service's graph on its detail screen, or nothing.
 *
 * It measures the room it has before it draws anything, because that decides what is asked for;
 * and when there is no graph it draws nothing at all: no heading, no gap, no message. Most
 * services on most servers have none.
 */
@Composable
fun GraphSection(state: GraphUiState, actions: GraphActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val size = GraphSize.inline(maxWidth.value.toInt())
        LaunchedEffect(size) { actions.show(size) }
        val decoded by rememberGraphBitmap(state.graph)
        // A picture the decoder would not open is no graph, like every other way of not having one.
        if (state.visible && !(decoded.failed(state.graph) && !state.loading)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(GraphTags.SECTION),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.graph_title), style = MaterialTheme.typography.titleMedium)
                PeriodChips(state.period, actions::choose)
                InlinePicture(state, decoded, placeholder = size, onOpen = actions::open)
                state.graph?.pageUrl?.let { url ->
                    TextButton(
                        onClick = { actions.openInBrowser(url) },
                        modifier = Modifier.testTag(GraphTags.BROWSER),
                    ) { Text(stringResource(R.string.graph_open_browser)) }
                }
            }
        }
    }
}

@Composable
internal fun PeriodChips(selected: GraphPeriod, onChoose: (GraphPeriod) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GraphPeriod.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onChoose(period) },
                label = { Text(stringResource(period.label())) },
                modifier = Modifier.testTag(GraphTags.period(period)),
            )
        }
    }
}

/**
 * The picture, in a frame that is the picture's own shape before the picture is there, so that
 * nothing below it moves when it arrives.
 */
@Composable
private fun InlinePicture(state: GraphUiState, decoded: DecodedGraph, placeholder: GraphSize, onOpen: () -> Unit) {
    val shape = state.graph?.let { it.width.toFloat() / it.height }
        ?: decoded.of?.let { it.width.toFloat() / it.height }
        ?: ((placeholder.width + PLACEHOLDER_EXTRA_WIDTH).toFloat() / (placeholder.height + PLACEHOLDER_EXTRA_HEIGHT))
    GraphPaper(modifier = Modifier.fillMaxWidth().aspectRatio(shape)) {
        val bitmap = decoded.bitmap
        if (bitmap != null) {
            val description = description(R.string.graph_description, state.service, decoded.of?.period ?: state.period)
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // Drawn small and shown larger: without this the labels are blocks.
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = description }
                    .testTag(GraphTags.PICTURE),
            )
        }
        if (bitmap == null || state.loading) Loading()
    }
}

/**
 * What a graph is shown on. White whatever the theme: nagiosgraph drew the picture for a white
 * page, with dark text and a pale background of its own, and it is shown as it was drawn.
 */
@Composable
internal fun GraphPaper(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(CORNER.dp), modifier = modifier) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun Loading() {
    val description = stringResource(R.string.graph_loading)
    CircularProgressIndicator(
        modifier = Modifier.size(SPINNER.dp).semantics { contentDescription = description }.testTag(GraphTags.LOADING),
    )
}

@Composable
internal fun description(text: Int, service: String, period: GraphPeriod): String =
    stringResource(text, service, stringResource(period.label()).lowercase())

internal fun GraphPeriod.label(): Int = when (this) {
    GraphPeriod.DAY -> R.string.graph_period_day
    GraphPeriod.WEEK -> R.string.graph_period_week
    GraphPeriod.MONTH -> R.string.graph_period_month
    GraphPeriod.YEAR -> R.string.graph_period_year
}

/** Shown before any picture has been: what rrdtool adds to a plot with one thing in it (measured). */
private const val PLACEHOLDER_EXTRA_WIDTH = 81
private const val PLACEHOLDER_EXTRA_HEIGHT = 55
private const val CORNER = 8
private const val SPINNER = 24
