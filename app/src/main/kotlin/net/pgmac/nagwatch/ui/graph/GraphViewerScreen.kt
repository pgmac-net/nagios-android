// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
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
fun GraphViewerScreen(onBack: () -> Unit, viewModel: GraphViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.show(GraphSize.FULL) }
    LandscapeWhileShown()
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
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(GraphTags.VIEWER),
        topBar = {
            TopAppBar(
                title = { Text(state.service, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(GraphTags.VIEWER_BACK)) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val decoded by rememberGraphBitmap(state.graph)
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // A row of their own, which scrolls. In the bar they pushed the title out and sat on
            // top of Back whenever the window was narrow: before the screen has turned, or split.
            PeriodChips(
                selected = state.period,
                onChoose = onChoose,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            )
            ViewerPicture(state, decoded, Modifier.fillMaxWidth().weight(1f).padding(8.dp))
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

            // Here the person asked for a graph, so here they are told there is none.
            else -> Text(
                text = stringResource(R.string.graph_none),
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
                    detectTransformGestures { _, pan, zoomBy, _ ->
                        zoom =
                            zoom.moved(zoomBy, pan.x, pan.y, width, height)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = zoom.toggled() }) }
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

/**
 * Turns the screen on its side while the viewer is up, and puts it back as it was after: a
 * graph is three times as wide as it is tall.
 *
 * What it was is saved, not read again: turning the screen rebuilds the activity, and a second
 * look would find "landscape" and restore that for ever. For the same reason nothing is put
 * back when the viewer goes only because the activity is being rebuilt.
 */
@Composable
private fun LandscapeWhileShown() {
    val activity = LocalActivity.current ?: return
    val before = rememberSaveable { activity.requestedOrientation }
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (!activity.isChangingConfigurations) activity.requestedOrientation = before }
    }
}
