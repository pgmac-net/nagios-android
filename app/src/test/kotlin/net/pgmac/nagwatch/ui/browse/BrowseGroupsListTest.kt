// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.ConfigPart
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Hosts tab's grouped view as the person works it (pgmac-net/nagios-android#82). */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class BrowseGroupsListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val loads = mutableListOf<Boolean>()
    private var groups by mutableStateOf(ConfigPart<List<HostGroup>>())
    private var parents by mutableStateOf(ConfigPart<List<HostConfig>>())
    private val parentLoads = mutableListOf<Boolean>()

    private val hosts = listOf(
        HostStatus("web01", HostState.DOWN, CheckStatus(pluginOutput = "no route")),
        HostStatus("web02", HostState.UP, CheckStatus(pluginOutput = "PING OK")),
        HostStatus("db01", HostState.UP, CheckStatus(pluginOutput = "PING OK")),
        HostStatus("lone", HostState.UP, CheckStatus(pluginOutput = "PING OK")),
    )
    private val known = listOf(
        HostGroup("web", "Web servers", listOf("web01", "web02")),
        HostGroup("db", "Databases", listOf("db01")),
    )

    @Test
    fun `the view switch starts on All, and the groups are read only when Groups is chosen`() {
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.ALL)).assertIsSelected()
        assertEquals(emptyList<Boolean>(), loads)

        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()
        composeRule.waitForIdle()

        assertEquals("asked once, without forcing", listOf(false), loads)
        composeRule.onNodeWithTag(BrowseTags.GROUPS_PENDING).assertExists()
    }

    @Test
    fun `groups are shown with their members, one with a problem starting open, the others closed`() {
        groups = ConfigPart(known)
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()

        composeRule.onNodeWithText("Web servers").assertExists()
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertExists()
        composeRule.onNodeWithText("Databases").assertExists()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertDoesNotExist()
        composeRule.onNodeWithTag(BrowseTags.LIST).performScrollToNode(hasText("Not in a group"))
        composeRule.onNodeWithText("Not in a group").assertExists()
    }

    @Test
    fun `a closed group opens when tapped, and its hosts open their detail`() {
        groups = ConfigPart(known)
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.group("db")).performClick()
        composeRule.onNodeWithTag(BrowseTags.LIST).performScrollToNode(hasTestTag(BrowseTags.row("db01")))
        composeRule.onNodeWithTag(BrowseTags.row("db01")).performClick()

        assertEquals(listOf("db01"), opened)
    }

    @Test
    fun `a failed read says why and can be tried again, forcing a read`() {
        groups = ConfigPart(error = StatusError.Nagios(NagiosError.Forbidden))
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.GROUPS_RETRY).performClick()

        assertEquals("the failure is not retried by itself, only by the button", listOf(true), loads)
    }

    @Test
    fun `a Nagios with no groups says so and still lists everything`() {
        groups = ConfigPart(emptyList())
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.GROUPS_NONE).assertExists()
        composeRule.onNodeWithText("Not in a group").assertExists()
    }

    @Test
    fun `Groups opens on every state, and All keeps the chips it had`() {
        groups = ConfigPart(known)
        show(initialStates = HostStyle.defaultStates)
        composeRule.onNodeWithTag(BrowseTags.row("web02")).assertDoesNotExist()

        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()
        composeRule.onNodeWithTag(BrowseTags.group("db")).performClick()
        composeRule.onNodeWithTag(BrowseTags.LIST).performScrollToNode(hasTestTag(BrowseTags.row("db01")))
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertExists()

        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.ALL)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertDoesNotExist()
        composeRule.onNodeWithTag(BrowseTags.chip(HostState.DOWN)).assertIsSelected()
    }

    @Test
    fun `Parents shows the tree, reads it once, and a branch opens to the hosts under it`() {
        parents = ConfigPart(listOf(HostConfig("web02", listOf("db01")), HostConfig("web01", listOf("db01"))))
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.PARENTS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.tree(HostTree.key("", "db01"))).assertExists()
        composeRule.onNodeWithTag(BrowseTags.treeToggle(HostTree.key("", "db01"))).assertExists()
        // web01 is down and unhandled, so its branch starts open.
        composeRule.onNodeWithTag(BrowseTags.tree(HostTree.key(HostTree.key("", "db01"), "web01"))).assertExists()
        composeRule.onNodeWithTag(BrowseTags.treeToggle(HostTree.key("", "db01"))).performClick()
        composeRule.onNodeWithTag(BrowseTags.tree(HostTree.key(HostTree.key("", "db01"), "web01"))).assertDoesNotExist()
        assertEquals("already known, not read again", emptyList<Boolean>(), parentLoads)
    }

    @Test
    fun `a failed read of the parents says it was the parents`() {
        parents = ConfigPart(error = StatusError.Nagios(NagiosError.Forbidden))
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.PARENTS)).performClick()

        composeRule.onNodeWithText("Couldn\u2019t read the hosts\u2019 parents.", substring = true).assertExists()
    }

    @Test
    fun `group names a server could make collide do not take the list down`() {
        // A group named after another group's key, a repeated name, and a blank one.
        groups = ConfigPart(
            listOf(
                HostGroup("group", "", listOf("web01", "web02")),
                HostGroup("web01", "", listOf("web01")),
                HostGroup("web01", "again", listOf("db01")),
                HostGroup("", "", listOf("lone")),
            ),
        )
        show()
        composeRule.onNodeWithTag(BrowseTags.mode(BrowseMode.GROUPS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.LIST).assertExists()
    }

    private fun show(initialStates: Set<HostState> = emptySet()) {
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
                    initialStates = initialStates,
                    groups = GroupsSource(groups.mapValue(BrowseGroups::hostGroups)) { loads += it },
                    parents = ParentsSource(parents) { parentLoads += it },
                )
            }
        }
    }
}
