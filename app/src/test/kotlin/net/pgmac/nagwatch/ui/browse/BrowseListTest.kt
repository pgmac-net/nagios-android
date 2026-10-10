// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.home.HomeTags
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Hosts and Services lists as the user sees and works them. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class BrowseListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private val hosts = listOf(
        HostStatus(
            "web01",
            HostState.UP,
            CheckStatus(pluginOutput = "PING OK", lastStateChange = T0.minusSeconds(7200)),
        ),
        HostStatus(
            "web02",
            HostState.DOWN,
            CheckStatus(pluginOutput = "no route", lastStateChange = T0.minusSeconds(60)),
        ),
        HostStatus("db01", HostState.DOWN, CheckStatus(acknowledged = true, pluginOutput = "timeout")),
    )
    private val services = listOf(
        ServiceStateEntry("web01", "Ping", ServiceState.OK),
        ServiceStateEntry("web01", "Disk /", ServiceState.CRITICAL),
        ServiceStateEntry("web01", "Load", ServiceState.WARNING),
    )
    private val problems = listOf(
        ServiceStatus(
            "web01",
            "Disk /",
            ServiceState.CRITICAL,
            CheckStatus(pluginOutput = "97% used", lastStateChange = T0),
        ),
        ServiceStatus("web01", "Load", ServiceState.WARNING, CheckStatus(downtimeDepth = 1, pluginOutput = "load 9")),
    )

    @Test
    fun `hosts are listed with state, how long for, output and how they are handled`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
        composeRule.onNodeWithText("PING OK").assertIsDisplayed()
        composeRule.onNodeWithText("2h 00m").assertIsDisplayed()
        composeRule.onNodeWithText("no route").assertIsDisplayed()
        composeRule.onNodeWithText("acknowledged").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.UPDATED).assertIsDisplayed()
    }

    @Test
    fun `the chips count each state`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).assertTextContains("UP (1)")
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.DOWN)).assertTextContains("DOWN (2)")
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UNREACHABLE)).assertTextContains("UNREACH (0)")
        composeRule.onNodeWithTag(BrowseTags.HANDLED).assertTextContains("Handled (1)")
    }

    @Test
    fun `a state nothing is in has no chip when it is a rare one`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.PENDING)).assertDoesNotExist()
    }

    @Test
    fun `typing in the search box narrows the list, and Clear brings it back`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.SEARCH).performTextInput("WEB")

        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertDoesNotExist()
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.DOWN)).assertTextContains("DOWN (1)")

        composeRule.onNodeWithTag(BrowseTags.CLEAR).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertIsDisplayed()
    }

    @Test
    fun `a search that finds nothing says so, rather than looking like there are no hosts`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.SEARCH).performTextInput("zzz")

        composeRule.onNodeWithTag(BrowseTags.EMPTY).assertTextContains("Nothing matches.")
    }

    @Test
    fun `a Nagios with no hosts says that, not that nothing matches`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) { BrowseList(emptyList(), HostStyle, status(), T0, onOpen = {}) }
        }

        composeRule.onNodeWithTag(BrowseTags.EMPTY).assertTextContains("Nagios reported no hosts.")
    }

    @Test
    fun `tapping a state chip shows only that state, and tapping it again shows everything`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web02")).assertDoesNotExist()

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("web02")).assertIsDisplayed()
    }

    @Test
    fun `switching Handled off hides acknowledged problems and keeps the switch, so they can come back`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.HANDLED).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertDoesNotExist()
        composeRule.onNodeWithTag(BrowseTags.row("web02")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.DOWN)).assertTextContains("DOWN (1)")

        composeRule.onNodeWithTag(BrowseTags.HANDLED).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertIsDisplayed()
    }

    @Test
    fun `Hosts opens on what is down or unreachable, and the rest is one tap away`() {
        showHosts(HostStyle.defaultStates)

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.DOWN)).assertIsSelected()
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UNREACHABLE)).assertIsSelected()
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).assertIsNotSelected().assertTextContains("UP (1)")
        composeRule.onNodeWithTag(BrowseTags.row("web02")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertDoesNotExist()

        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).performClick()

        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
    }

    @Test
    fun `Services opens on warnings and criticals`() {
        showServices(ServiceStyle.defaultStates)

        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.WARNING)).assertIsSelected()
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.CRITICAL)).assertIsSelected()
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.OK)).assertIsNotSelected()
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.UNKNOWN)).assertIsNotSelected()
        composeRule.onNodeWithTag(BrowseTags.row("web01 / Disk /")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web01 / Load")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web01 / Ping")).assertDoesNotExist()
    }

    @Test
    fun `the default chips are exactly the ones asked for`() {
        assertEquals(setOf(HostState.DOWN, HostState.UNREACHABLE), HostStyle.defaultStates)
        assertEquals(setOf(ServiceState.WARNING, ServiceState.CRITICAL), ServiceStyle.defaultStates)
    }

    @Test
    fun `when nothing is down the list says so, and the chips show where the hosts are`() {
        showHosts(HostStyle.defaultStates, hosts = listOf(HostStatus("web01", HostState.UP, CheckStatus())))

        composeRule.onNodeWithTag(BrowseTags.EMPTY).assertTextContains("Nothing is in the states that are switched on.")
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).assertTextContains("UP (1)")
    }

    @Test
    fun `a search that only matches a state that is switched off says where to look`() {
        showHosts(HostStyle.defaultStates)

        composeRule.onNodeWithTag(BrowseTags.SEARCH).performTextInput("web01")

        composeRule.onNodeWithTag(
            BrowseTags.EMPTY,
        ).assertTextContains("The chips show where the matches are", substring = true)
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.UP)).assertTextContains("UP (1)")
    }

    @Test
    fun `tapping a host asks to open it`() {
        showHosts()

        composeRule.onNodeWithTag(BrowseTags.row("web02")).performClick()

        assertEquals(listOf("web02"), opened)
    }

    @Test
    fun `services show the host and check name, with details only where there is a problem`() {
        showServices()

        composeRule.onNodeWithTag(BrowseTags.row("web01 / Ping")).assertIsDisplayed()
        composeRule.onNodeWithText("97% used").assertIsDisplayed()
        composeRule.onNodeWithText("in downtime").assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.OK)).assertTextContains("OK (1)")
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.CRITICAL)).assertTextContains("CRIT (1)")
    }

    @Test
    fun `tapping a service asks to open it by host and name`() {
        showServices()

        composeRule.onNodeWithTag(BrowseTags.row("web01 / Disk /")).performClick()

        assertEquals(listOf("web01 / Disk /"), opened)
    }

    @Test
    fun `a failed refresh and old data are announced above the list`() {
        val old = status().copy(error = StatusError.Nagios(NagiosError.BadCredentials))
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                BrowseList(hostRows(), HostStyle, old, T0.plus(Duration.ofHours(2)), onOpen = {})
            }
        }

        composeRule.onNodeWithTag(HomeTags.BANNER_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.BANNER_STALE).assertIsDisplayed()
    }

    // Real text measurement: with the default stub fonts every chip is a few dp wide and this
    // could not see chips squeezed or pushed off the screen.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
    fun `the service chips fit on a phone, each on one line`() {
        // As the tab opens: two chips on, each wider by its tick.
        showServices(ServiceStyle.defaultStates)

        val screenWidth = composeRule.onRoot().getUnclippedBoundsInRoot().width
        val tags = listOf(ServiceState.OK, ServiceState.WARNING, ServiceState.CRITICAL, ServiceState.UNKNOWN)
            .map(BrowseTags::chip) + BrowseTags.HANDLED
        tags.forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag is squeezed: ${bounds.width} wide", bounds.width > 48.dp)
            assertTrue("$tag wraps: ${bounds.height} tall", bounds.height < 64.dp)
            assertTrue("$tag runs off the screen", bounds.right <= screenWidth)
        }
        val firstRow = composeRule.onNodeWithTag(BrowseTags.row("web01 / Disk /")).getUnclippedBoundsInRoot()
        assertTrue("the rows start on the first screen: ${firstRow.top}", firstRow.top < 360.dp)
    }

    /** With every chip off unless told otherwise, so a test sees the whole list. */
    private fun showHosts(states: Set<HostState> = emptySet(), hosts: List<HostStatus> = this.hosts) {
        val snapshot = StatusSnapshot(hosts, emptyList(), T0)
        val report = ProblemClassifier.classify(snapshot)
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                BrowseList(
                    rows = BrowseRows.hosts(snapshot, report),
                    style = HostStyle,
                    status = ProfileStatus(report = report, snapshot = snapshot, lastSuccess = T0),
                    now = T0,
                    onOpen = { opened += it.title },
                    initialStates = states,
                )
            }
        }
    }

    private fun showServices(states: Set<ServiceState> = emptySet()) = composeRule.setContent {
        NagwatchTheme(dynamicColor = false) {
            BrowseList(
                rows = BrowseRows.services(snapshot(), ProblemClassifier.classify(snapshot())),
                style = ServiceStyle,
                status = status(),
                now = T0,
                onOpen = { opened += it.title },
                initialStates = states,
            )
        }
    }

    private fun hostRows() = BrowseRows.hosts(snapshot(), ProblemClassifier.classify(snapshot()))

    private fun snapshot() = StatusSnapshot(hosts, problems, T0, services)

    private fun status() =
        ProfileStatus(report = ProblemClassifier.classify(snapshot()), snapshot = snapshot(), lastSuccess = T0)
}
