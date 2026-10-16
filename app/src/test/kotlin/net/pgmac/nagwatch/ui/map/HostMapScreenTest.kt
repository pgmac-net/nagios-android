// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.status.ConfigPart
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.graph.Zoom
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The map screen as the person works it (pgmac-net/nagios-android#95). */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class HostMapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val retries = mutableListOf<Unit>()

    private companion object {
        const val DOUBLE_TAP_WAIT_MS = 500L
    }

    private fun show(state: HostMapUiState) = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            HostMapContent(state, onBack = {}, onOpen = { opened += it }, onRetry = { retries += Unit })
        }
    }

    @Test
    fun `a tap on a host's dot asks to open that host`() {
        val layout = HostMap.layout(listOf("router", "web01"), listOf(HostConfig("web01", listOf("router"))))
        show(HostMapUiState(layout))
        val canvas = composeRule.onNodeWithTag(HostMapTags.CANVAS)
        val size = canvas.fetchSemanticsNode().size
        val web01 = layout.nodes.single { it.name == "web01" }
        val (x, y) = mapPoint(web01, size.width.toFloat(), size.height.toFloat(), Zoom())

        canvas.performTouchInput { click(Offset(x, y)) }
        // A single tap is only known once a second one has not followed it.
        composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("web01"), opened)
    }

    @Test
    fun `an install too large to draw says so instead`() {
        show(HostMapUiState(HostMapLayout(emptyList(), emptyList(), tooLarge = true)))

        composeRule.onNodeWithTag(HostMapTags.TOO_LARGE).assertExists()
        composeRule.onNodeWithTag(HostMapTags.CANVAS).assertDoesNotExist()
    }

    @Test
    fun `parents that could not be read say why and can be tried again`() {
        show(HostMapUiState(configs = ConfigPart<Unit>(error = StatusError.Nagios(NagiosError.Forbidden))))

        composeRule.onNodeWithTag(HostMapTags.RETRY).performClick()

        assertEquals(1, retries.size)
    }
}
