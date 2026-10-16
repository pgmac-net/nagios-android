// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.status.ConfigPart
import net.pgmac.nagwatch.status.InventoryRepository
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.ui.NagwatchTopAppBar
import net.pgmac.nagwatch.ui.browse.BrowseRow
import net.pgmac.nagwatch.ui.browse.BrowseRows
import net.pgmac.nagwatch.ui.browse.HostStyle
import net.pgmac.nagwatch.ui.graph.Zoom
import net.pgmac.nagwatch.ui.home.message
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.theme.StateColors

object HostMapTags {
    const val CANVAS = "map_canvas"
    const val PENDING = "map_pending"
    const val RETRY = "map_retry"
    const val TOO_LARGE = "map_too_large"
    const val BACK = "map_back"
}

/** What the map draws: the layout, and each host's row for its state. Null while it is not known. */
data class HostMapUiState(
    val layout: HostMapLayout? = null,
    val rows: Map<String, BrowseRow<HostState>> = emptyMap(),
    /** The parents, being read or failed to be. */
    val configs: ConfigPart<*> = ConfigPart<Unit>(),
)

@HiltViewModel
class HostMapViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val inventory: InventoryRepository,
    private val statuses: StatusRepository,
) : ViewModel() {
    private val profileId: Long = checkNotNull(savedState[ARG_PROFILE]) { "no profile" }

    val state: StateFlow<HostMapUiState> = combine(inventory.inventories, statuses.statuses) { inventories, all ->
        val configs = inventories[profileId]?.hostConfigs ?: ConfigPart()
        val status = all[profileId]
        val snapshot = status?.snapshot
        val report = status?.report
        val known = configs.value
        if (known == null || snapshot == null || report == null) {
            HostMapUiState(configs = configs)
        } else {
            // In the order the Hosts list has them: worst first, so trouble is spread first.
            val rows = BrowseRows.hosts(snapshot, report)
            HostMapUiState(HostMap.layout(rows.map { it.hostName }, known), rows.associateBy { it.hostName }, configs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HostMapUiState())

    init {
        // Opened straight from the Parents view both are known; after a restore neither may be.
        load(force = false)
        viewModelScope.launch { statuses.refreshIfOlderThan(profileId, STATUS_MAX_AGE) }
    }

    fun load(force: Boolean) {
        viewModelScope.launch { inventory.loadHostConfigs(profileId, force) }
    }

    companion object {
        const val ARG_PROFILE = "profile"
        private const val STOP_TIMEOUT_MS = 5_000L
        private val STATUS_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}

@Composable
fun HostMapScreen(
    onBack: () -> Unit,
    onOpen: (hostName: String) -> Unit,
    viewModel: HostMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HostMapContent(state, onBack, onOpen, onRetry = { viewModel.load(force = true) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostMapContent(state: HostMapUiState, onBack: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            NagwatchTopAppBar(
                title = { Text(stringResource(R.string.map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(HostMapTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val layout = state.layout
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                layout == null -> Pending(state.configs.error, onRetry)

                layout.tooLarge -> Message(
                    pluralStringResource(R.plurals.map_too_large, HostMap.MAX_HOSTS, HostMap.MAX_HOSTS),
                    HostMapTags.TOO_LARGE,
                )

                else -> MapCanvas(layout, state.rows, onOpen)
            }
        }
    }
}

@Composable
private fun Pending(error: StatusError?, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp).testTag(HostMapTags.PENDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error == null) {
            Text(stringResource(R.string.browse_parents_loading), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                stringResource(R.string.browse_parents_failed, error.message()),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag(HostMapTags.RETRY)) {
                Text(stringResource(R.string.browse_groups_retry))
            }
        }
    }
}

@Composable
private fun Message(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp).testTag(tag))
}

/**
 * Where a map point is drawn in a frame of [width] by [height] at [zoom]: the circle fills most
 * of the shorter side when not zoomed, and the zoom moves points rather than magnifying the
 * picture, so dots and names stay the same size on screen.
 */
internal fun mapPoint(node: MapNode, width: Float, height: Float, zoom: Zoom): Pair<Float, Float> {
    val radius = min(width, height) / 2f * MAP_FILL * zoom.scale
    return Pair(width / 2f + zoom.offsetX + node.x * radius, height / 2f + zoom.offsetY + node.y * radius)
}

private val ZoomSaver = listSaver<Zoom, Float>(
    save = { listOf(it.scale, it.offsetX, it.offsetY) },
    restore = { (scale, x, y) -> Zoom(scale, x, y, MAP_MAX_ZOOM) },
)

@Composable
private fun MapCanvas(layout: HostMapLayout, rows: Map<String, BrowseRow<HostState>>, onOpen: (String) -> Unit) {
    var zoom by rememberSaveable(stateSaver = ZoomSaver) { mutableStateOf(Zoom(max = MAP_MAX_ZOOM)) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val colors = NagwatchTheme.stateColors
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val centreColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val needing = rows.values.count { it.needsSomeone() }
    val description = pluralStringResource(R.plurals.map_description, layout.nodes.size, layout.nodes.size, needing)
    val byName = remember(layout) { layout.nodes.associateBy { it.name } }
    val reach = with(density) { TAP_REACH.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .testTag(HostMapTags.CANVAS)
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoomBy, _ ->
                    zoom =
                        zoom.moved(
                            zoomBy,
                            pan.x,
                            pan.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            centroid.x,
                            centroid.y,
                        )
                }
            }
            .pointerInput(layout) {
                val (w, h) = size.width.toFloat() to size.height.toFloat()
                detectTapGestures(
                    onDoubleTap = { at -> zoom = zoom.toggled(w, h, at.x, at.y) },
                    onTap = { at ->
                        HostMap.hit(layout.nodes, at.x, at.y, reach) {
                            mapPoint(it, w, h, zoom)
                        }?.let { onOpen(it.name) }
                    },
                )
            },
    ) {
        val (w, h) = size.width to size.height
        drawMap(
            layout = layout,
            rows = rows,
            byName = byName,
            centre = Offset(w / 2f + zoom.offsetX, h / 2f + zoom.offsetY),
            at = { node -> mapPoint(node, w, h, zoom).let { (x, y) -> Offset(x, y) } },
            look = MapLook(colors, lineColor, centreColor, labelStyle, measurer),
        )
    }
}

/** How the map is drawn: the theme's colours and the label's text, gathered for [drawMap]. */
private class MapLook(
    val states: StateColors,
    val line: Color,
    val centre: Color,
    val label: TextStyle,
    val measurer: TextMeasurer,
)

/** Lines, then the middle, then dots and names: problems first, so their names are never left out. */
@Suppress("LongParameterList") // What a drawing needs to know.
private fun DrawScope.drawMap(
    layout: HostMapLayout,
    rows: Map<String, BrowseRow<HostState>>,
    byName: Map<String, MapNode>,
    centre: Offset,
    at: (MapNode) -> Offset,
    look: MapLook,
) {
    val thin = 1.dp.toPx()
    layout.edges.forEach { edge ->
        val from = edge.parent?.let { byName[it] }?.let(at) ?: centre
        byName[edge.host]?.let { drawLine(look.line, from, at(it), strokeWidth = thin) }
    }
    drawCircle(look.centre, radius = DOT.toPx(), center = centre)

    val taken = mutableListOf<Rect>()
    layout.nodes.sortedBy { if (rows[it.name]?.needsSomeone() == true) 0 else 1 }.forEach { node ->
        val row = rows[node.name]
        val point = at(node)
        val big = row?.needsSomeone() == true
        val radius = (if (big) BIG_DOT else DOT).toPx()
        val color = row?.let { HostStyle.color(it.state, look.states) } ?: look.states.pending
        drawCircle(color, radius = radius, center = point)
        if (big) drawCircle(color, radius = radius + RING_GAP.toPx(), center = point, style = Stroke(thin))

        val text = look.measurer.measure(node.name, look.label)
        val topLeft = Offset(point.x + radius + LABEL_GAP.toPx(), point.y - text.size.height / 2f)
        val box = Rect(topLeft, Size(text.size.width.toFloat(), text.size.height.toFloat()))
        // A name that would sit on another is left out until the map is zoomed apart.
        if (big || taken.none { it.overlaps(box) }) {
            taken += box
            drawText(text, topLeft = topLeft)
        }
    }
}

private fun BrowseRow<HostState>.needsSomeone(): Boolean = handling != null && !isHandled

/** The circle takes this much of the shorter side, leaving room for the outer ring's names. */
private const val MAP_FILL = 0.8f

/** A drawing stays sharp however far it is magnified; past this the rings are far apart on any install. */
private const val MAP_MAX_ZOOM = 12f
private val DOT = 6.dp
private val BIG_DOT = 9.dp
private val RING_GAP = 3.dp
private val LABEL_GAP = 4.dp

/** A fingertip's reach: a tap this close to a dot is a tap on it. */
private val TAP_REACH = 24.dp
