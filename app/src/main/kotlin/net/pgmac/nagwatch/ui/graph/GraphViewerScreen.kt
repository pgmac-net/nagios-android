// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphSize

/**
 * A service's graph with the screen to itself, on its side, where it can be pinched and dragged.
 * It asks for its own, larger picture; the one on the detail screen magnified would be a blur.
 */
@Composable
fun GraphViewerScreen(turn: ScreenTurn, onBack: () -> Unit, viewModel: GraphViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.show(GraphSize.FULL) }
    // On its side while it is up: a graph is three times as wide as it is tall. What happens
    // when it goes is ScreenTurn's business, and outlives this screen.
    DisposableEffect(turn) {
        turn.sideways()
        onDispose { turn.back() }
    }
    GraphViewerContent(state, onBack = onBack, onChoose = viewModel::choose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphViewerContent(
    state: GraphUiState,
    onBack: () -> Unit,
    onChoose: (GraphPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(GraphTags.VIEWER)) {
        // On its side a phone is short, and a row for the periods costs a fifth of the picture.
        // They go in the bar where the bar has room for them and the title both, and only there:
        // in a narrow bar they pushed the title out and sat on top of Back.
        val periodsInBar = maxWidth >= PERIODS_IN_BAR_FROM.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(GraphTags.VIEWER_TITLE),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(GraphTags.VIEWER_BACK)) {
                            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                        }
                    },
                    actions = { if (periodsInBar) PeriodChips(state.period, onChoose, Modifier.padding(end = 16.dp)) },
                )
            },
        ) { innerPadding ->
            val decoded by rememberGraphBitmap(state.graph)
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (!periodsInBar) {
                    PeriodChips(
                        selected = state.period,
                        onChoose = onChoose,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    )
                }
                ViewerPicture(state, decoded, Modifier.fillMaxWidth().weight(1f).padding(8.dp))
            }
        }
    }
}

/** The picture, or a sign that it is coming, or the plain statement that there is none. */
@Composable
private fun ViewerPicture(state: GraphUiState, decoded: DecodedGraph, modifier: Modifier = Modifier) {
    val bitmap = decoded.bitmap
    GraphPaper(modifier = modifier) {
        when {
            bitmap != null -> {
                val shown = decoded.of?.period ?: state.period
                ZoomablePicture(bitmap, description(R.string.graph_description_full, state.service, shown))
                if (state.loading) Loading()
            }

            // Still on its way: being fetched, or fetched and being decoded.
            state.loading || (state.graph != null && !decoded.failed(state.graph)) -> Loading()

            // Here the person asked for a graph, so here they are told there is none, or that
            // nobody could be asked.
            else -> Text(
                text = stringResource(if (state.unreachable) R.string.graph_unreachable else R.string.graph_none),
                color = Color.DarkGray,
                modifier = Modifier.padding(16.dp).testTag(GraphTags.VIEWER_NONE),
            )
        }
    }
}

/** A zoom is three numbers; kept through the screen being turned. */
private val ZoomSaver = listSaver<Zoom, Float>(
    save = { listOf(it.scale, it.offsetX, it.offsetY) },
    restore = { Zoom(it[0], it[1], it[2]) },
)

/**
 * The picture, pinched and dragged. It starts fitted to its frame; the limits are [Zoom]'s. A
 * new picture (another period) starts fitted again.
 */
@Composable
private fun ZoomablePicture(bitmap: ImageBitmap, description: String) {
    var zoom by rememberSaveable(bitmap, stateSaver = ZoomSaver) { mutableStateOf(Zoom()) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(width, height) {
                    // About the fingers, not about the middle: what is being looked at stays put.
                    detectTransformGestures { centroid, pan, zoomBy, _ ->
                        zoom = zoom.moved(zoomBy, pan.x, pan.y, width, height, centroid.x, centroid.y)
                    }
                }
                .pointerInput(width, height) {
                    detectTapGestures(onDoubleTap = { at -> zoom = zoom.toggled(width, height, at.x, at.y) })
                }
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                }
                .semantics { contentDescription = description }
                .testTag(GraphTags.VIEWER_PICTURE),
        )
    }
}

/** Four chips are about 330dp, and a title wants about as much again. */
private const val PERIODS_IN_BAR_FROM = 700
