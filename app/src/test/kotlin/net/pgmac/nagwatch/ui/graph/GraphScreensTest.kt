// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import net.pgmac.nagwatch.nagios.graph.Captures
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.graph.png
import net.pgmac.nagwatch.status.GraphState
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The graph on a detail screen and in its own viewer, drawn from real pictures: the captures in
 * `/graphs` are decoded by the platform's decoder, which is why this needs native graphics.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(GraphUiState(service = "Disk /"))
    private val calls = mutableListOf<String>()
    private val actions = object : GraphActions {
        override fun show(size: GraphSize) {
            calls += "show ${size.geom}"
        }

        override fun choose(period: GraphPeriod) {
            calls += "choose $period"
        }

        override fun open() {
            calls += "open"
        }

        override fun openInBrowser(url: String) {
            calls += "browser $url"
        }
    }
    private val day = shown("graph_day_360x120.png", 441, 175, GraphPeriod.DAY)

    // ---------------------------------------------------------------- decoding

    @Test
    fun `a real graph is decoded at the size its header gave`() {
        val bitmap = checkNotNull(GraphBitmap.decode(day))

        assertEquals(441 to 175, bitmap.width to bitmap.height)
    }

    @Test
    fun `a picture the decoder sizes differently from the header is not drawn`() {
        assertNull(GraphBitmap.decode(shown("graph_day_360x120.png", 441, 176, GraphPeriod.DAY)))
        assertNull(GraphBitmap.decode(shown("graph_day_360x120.png", 440, 175, GraphPeriod.DAY)))
    }

    @Test
    fun `a header with nothing a decoder can use behind it is not drawn`() {
        val hollow = GraphState.Shown(png(441, 175) + ByteArray(64), 441, 175, GraphPeriod.DAY, Instant.EPOCH, null)

        assertNull(GraphBitmap.decode(hollow))
    }

    @Test
    fun `nagiosgraph's error picture decodes, which is why the rule and not the decoder decides`() {
        assertNotNull(GraphBitmap.decode(shown("no_data.png", 608, 23, GraphPeriod.DAY)))
    }

    // ---------------------------------------------------------------- on the detail screen

    @Test
    fun `with no graph nothing at all is drawn, and the room there is has still been reported`() {
        showSection(widthDp = 411)

        composeRule.onNodeWithTag(GraphTags.SECTION).assertDoesNotExist()
        composeRule.onNodeWithText("Graph").assertDoesNotExist()
        // 411 less the screen's 16 on each side is 379: room for a 240 plot and its axis.
        assertEquals(listOf("show 240x120"), calls)
    }

    @Test
    fun `a wider screen is asked for a wider graph, once`() {
        showSection(widthDp = 800)

        assertEquals(listOf("show 660x120"), calls)
    }

    @Test
    fun `a graph on its way has its heading, its periods and a place kept for it`() {
        state = state.copy(loading = true)
        showSection()

        composeRule.onNodeWithTag(GraphTags.SECTION).assertIsDisplayed()
        composeRule.onNodeWithText("Graph").assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.PICTURE).assertDoesNotExist()
        composeRule.onNodeWithTag(GraphTags.BROWSER).assertDoesNotExist()
    }

    @Test
    fun `a graph is drawn, described for someone who cannot see it, with its period chosen`() {
        state = state.copy(graph = day)
        showSection()
        awaitTag(GraphTags.PICTURE)

        composeRule.onNodeWithTag(GraphTags.PICTURE).assertIsDisplayed().assert(
            described("Graph of Disk / over the last day, drawn by nagiosgraph. Tap for full screen."),
        )
        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.DAY)).assertIsSelected()
        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.YEAR)).assertIsNotSelected()
        composeRule.onNodeWithTag(GraphTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `the four periods are offered and a tap chooses one`() {
        state = state.copy(graph = day)
        showSection()

        listOf("Day", "Week", "Month", "Year").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.MONTH)).performClick()

        assertTrue("choose MONTH" in calls)
    }

    @Test
    fun `a tap on the graph asks for the whole screen`() {
        state = state.copy(graph = day)
        showSection()
        awaitTag(GraphTags.PICTURE)

        composeRule.onNodeWithTag(GraphTags.PICTURE).performClick()

        assertTrue("open" in calls)
    }

    @Test
    fun `nagiosgraph's own page is offered only with a graph that has one`() {
        state = state.copy(graph = day)
        showSection()
        composeRule.onNodeWithTag(GraphTags.BROWSER).performClick()
        assertTrue("browser https://nagios.example.org/nagios/cgi-bin/show.cgi?host=web01&service=Load" in calls)

        state = state.copy(graph = shown("graph_day_360x120.png", 441, 175, GraphPeriod.DAY, pageUrl = null))
        composeRule.onNodeWithTag(GraphTags.BROWSER).assertDoesNotExist()
    }

    @Test
    fun `while another period loads, the picture already there stays, with a sign that more is coming`() {
        state = state.copy(graph = day)
        showSection()
        awaitTag(GraphTags.PICTURE)

        state = state.copy(period = GraphPeriod.WEEK, loading = true)

        composeRule.onNodeWithTag(GraphTags.PICTURE).assertIsDisplayed()
            .assert(described("Graph of Disk / over the last day, drawn by nagiosgraph. Tap for full screen."))
        composeRule.onNodeWithTag(GraphTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.WEEK)).assertIsSelected()
    }

    @Test
    fun `a graph that goes away takes its heading and its periods with it`() {
        state = state.copy(graph = day)
        showSection()
        awaitTag(GraphTags.PICTURE)

        state = state.copy(graph = null)

        composeRule.onNodeWithTag(GraphTags.SECTION).assertDoesNotExist()
        composeRule.onNodeWithText("Day").assertDoesNotExist()
    }

    @Test
    fun `a picture the decoder will not open is no graph, not a place kept for ever`() {
        state =
            state.copy(
                graph = GraphState.Shown(png(441, 175) + ByteArray(64), 441, 175, GraphPeriod.DAY, Instant.EPOCH, "x"),
            )
        showSection()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(GraphTags.SECTION).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(GraphTags.BROWSER).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- with the screen to itself

    @Test
    fun `the viewer shows the service's name, its periods and the picture, described`() {
        state =
            state.copy(graph = shown("graph_year_700x240.png", 781, 295, GraphPeriod.YEAR), period = GraphPeriod.YEAR)
        showViewer()
        awaitTag(GraphTags.VIEWER_PICTURE)

        composeRule.onNodeWithText("Disk /").assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.VIEWER_PICTURE).assertIsDisplayed().assert(
            described("Graph of Disk / over the last year, drawn by nagiosgraph. Pinch to zoom, double-tap to reset."),
        )
        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.YEAR)).assertIsSelected()
        composeRule.onNodeWithTag(GraphTags.VIEWER_NONE).assertDoesNotExist()
    }

    @Test
    fun `the viewer says so when there is no graph, because here one was asked for`() {
        showViewer()

        composeRule.onNodeWithTag(GraphTags.VIEWER_NONE).assertIsDisplayed()
        composeRule.onNodeWithText("There is no graph for this period.").assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `the viewer waits without saying there is none while one is on its way`() {
        state = state.copy(loading = true)
        showViewer()

        composeRule.onNodeWithTag(GraphTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(GraphTags.VIEWER_NONE).assertDoesNotExist()
    }

    @Test
    fun `the viewer's back and its periods do what they say`() {
        state = state.copy(graph = day)
        showViewer()

        composeRule.onNodeWithTag(GraphTags.period(GraphPeriod.WEEK)).performClick()
        composeRule.onNodeWithTag(GraphTags.VIEWER_BACK).performClick()

        assertEquals(listOf("choose WEEK", "back"), calls)
    }

    @Test
    fun `a picture the decoder will not open is no graph in the viewer either`() {
        state =
            state.copy(
                graph = GraphState.Shown(png(781, 295) + ByteArray(64), 781, 295, GraphPeriod.DAY, Instant.EPOCH, null),
            )
        showViewer()

        awaitTag(GraphTags.VIEWER_NONE)
    }

    private fun shown(
        name: String,
        width: Int,
        height: Int,
        period: GraphPeriod,
        pageUrl: String? = "https://nagios.example.org/nagios/cgi-bin/show.cgi?host=web01&service=Load",
    ) = GraphState.Shown(Captures.bytes(name), width, height, period, Instant.EPOCH, pageUrl)

    private fun described(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    private fun awaitTag(tag: String) = composeRule.waitUntil(TIMEOUT_MS) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun showSection(widthDp: Int = 411) = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) { Box(Modifier.requiredWidth(widthDp.dp)) { GraphSection(state, actions) } }
    }

    private fun showViewer() = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            GraphViewerContent(state, onBack = { calls += "back" }, onChoose = actions::choose)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
