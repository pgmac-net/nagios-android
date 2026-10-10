// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.browse.BrowseTags
import net.pgmac.nagwatch.ui.problems.ProblemKind
import net.pgmac.nagwatch.ui.problems.ProblemsTags
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Moving between the Problems, Hosts and Services tabs. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(HomeUiState(loading = false))
    private var activity: ComponentActivity? = null
    private val calls = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun refresh() {
            calls += "refresh"
        }

        override fun toggleFilter(kind: ProblemKind) {
            calls += "filter:$kind"
        }

        override fun selectProfile(id: Long) {
            calls += "select:$id"
        }

        override fun addProfile() {
            calls += "add"
        }

        override fun editProfile(id: Long) {
            calls += "edit:$id"
        }

        override fun manageProfiles() {
            calls += "manage"
        }

        override fun openHost(hostName: String) {
            calls += "host:$hostName"
        }

        override fun openService(hostName: String, description: String) {
            calls += "service:$hostName/$description"
        }
    }

    private val snapshot = StatusSnapshot(
        hosts = listOf(
            HostStatus("web01", HostState.UP, CheckStatus()),
            HostStatus("db01", HostState.UP, CheckStatus()),
        ),
        serviceProblems = listOf(
            ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(pluginOutput = "97% used")),
        ),
        fetchedAt = T0,
        serviceStates = listOf(
            ServiceStateEntry("web01", "Disk /", ServiceState.CRITICAL),
            ServiceStateEntry("db01", "Ping", ServiceState.OK),
        ),
    )

    @Test
    fun `the app opens on Problems, with the three tabs along the bottom`() {
        show()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.PROBLEMS)).assertIsSelected()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).assertIsNotSelected()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).assertIsNotSelected()
        composeRule.onNodeWithTag(ProblemsTags.LIST).assertIsDisplayed()
    }

    @Test
    fun `Hosts lists every host and Services every service`() {
        show()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertIsDisplayed()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("db01 / Ping")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web01 / Disk /")).assertIsDisplayed()
    }

    @Test
    fun `the profile switcher is there on every tab`() {
        show()

        HomeTab.entries.forEach { tab ->
            composeRule.onNodeWithTag(HomeTags.tab(tab)).performClick()
            composeRule.onNodeWithTag(HomeTags.PROFILE_MENU).assertIsDisplayed().assertTextContains("Home")
        }
    }

    @Test
    fun `each tab keeps its own search and filter while another is shown`() {
        show()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).performClick()
        composeRule.onNodeWithTag(BrowseTags.SEARCH).performTextInput("db")
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertDoesNotExist()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).performClick()
        composeRule.onNodeWithTag(BrowseTags.chip(ServiceState.CRITICAL)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("db01 / Ping")).assertDoesNotExist()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.PROBLEMS)).performClick()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).performClick()

        composeRule.onNodeWithTag(BrowseTags.SEARCH).assertTextContains("db")
        composeRule.onNodeWithTag(BrowseTags.row("db01")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertDoesNotExist()

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).performClick()

        composeRule.onNodeWithTag(BrowseTags.SEARCH).assertTextContains("Search services and hosts")
        composeRule.onNodeWithTag(BrowseTags.row("web01 / Disk /")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrowseTags.row("db01 / Ping")).assertDoesNotExist()
    }

    @Test
    fun `another profile starts with an empty search, not the previous profile's`() {
        show()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).performClick()
        composeRule.onNodeWithTag(BrowseTags.SEARCH).performTextInput("db")

        state = state.copy(selected = profile(2, "Office"))

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).assertIsSelected()
        composeRule.onNodeWithTag(BrowseTags.row("web01")).assertIsDisplayed()
    }

    @Test
    fun `back from another tab returns to Problems`() {
        show()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).performClick()

        // The handler is switched on when the screen redraws for the new tab; a person cannot
        // press Back within that frame, so neither does the test.
        composeRule.waitForIdle()

        composeRule.runOnUiThread { checkNotNull(activity).onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.PROBLEMS)).assertIsSelected()
        composeRule.onNodeWithTag(ProblemsTags.LIST).assertIsDisplayed()
    }

    @Test
    fun `tapping a row asks to open that host or service`() {
        show()

        composeRule.onNodeWithText("web01 / Disk /").performClick()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("db01")).performClick()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.SERVICES)).performClick()
        composeRule.onNodeWithTag(BrowseTags.row("db01 / Ping")).performClick()

        assertEquals(listOf("service:web01/Disk /", "host:db01", "service:db01/Ping"), calls)
    }

    @Test
    fun `with no profile there are no tabs to choose between`() {
        state = HomeUiState(loading = false)
        setContent()

        composeRule.onNodeWithTag(HomeTags.NO_PROFILES).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.tab(HomeTab.HOSTS)).assertDoesNotExist()
    }

    @Test
    fun `a first failure reads the same on every tab, and the tabs stay`() {
        state = withStatus(ProfileStatus(error = StatusError.Nagios(NagiosError.BadCredentials)))
        setContent()

        HomeTab.entries.forEach { tab ->
            composeRule.onNodeWithTag(HomeTags.tab(tab)).performClick()
            composeRule.onNodeWithTag(HomeTags.ERROR).assertIsDisplayed()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
    fun `the three tabs share the width of a phone, each label on one line`() {
        show()

        val screenWidth = composeRule.onRoot().getUnclippedBoundsInRoot().width
        HomeTab.entries.forEach { tab ->
            val bounds = composeRule.onNodeWithTag(HomeTags.tab(tab)).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tab is squeezed: ${bounds.width}", bounds.width > 90.dp)
            assertTrue("$tab is too tall, so its label wrapped: ${bounds.height}", bounds.height < 96.dp)
            assertTrue("$tab runs off the screen", bounds.right <= screenWidth)
        }
    }

    private fun show() {
        state = withStatus(
            ProfileStatus(report = ProblemClassifier.classify(snapshot), snapshot = snapshot, lastSuccess = T0),
        )
        setContent()
    }

    private fun setContent() = composeRule.setContent {
        activity = LocalActivity.current as? ComponentActivity
        NagwatchTheme(dynamicColor = false) { HomeContent(state, actions) }
    }

    private fun withStatus(status: ProfileStatus) = HomeUiState(
        loading = false,
        profiles = listOf(profile(1, "Home"), profile(2, "Office")),
        selected = profile(1, "Home"),
        status = status,
        now = T0,
    )

    private fun profile(id: Long, name: String) = Profile(
        id = id,
        name = name,
        baseUrl = "https://nagios.example.org",
        username = "u",
        hasPassword = true,
        accessClientId = "",
        hasAccessClientSecret = false,
        customHeaderNames = emptyList(),
        allowCleartext = false,
    )
}
