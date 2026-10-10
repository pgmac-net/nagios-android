// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.snapshot
import net.pgmac.nagwatch.ui.home.HomeActions
import net.pgmac.nagwatch.ui.home.HomeContent
import net.pgmac.nagwatch.ui.home.HomeTags
import net.pgmac.nagwatch.ui.home.HomeUiState
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Problems screen as the user sees it, for each state it can be in. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class ProblemsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(HomeUiState(loading = false))
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

    @Test
    fun `before profiles arrive it says loading`() {
        show(HomeUiState(loading = true))

        composeRule.onNodeWithTag(HomeTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun `with no profile it explains and offers to add one`() {
        show(HomeUiState(loading = false))

        composeRule.onNodeWithTag(HomeTags.NO_PROFILES).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.ADD_PROFILE).performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun `before the first result it says loading rather than showing an empty list`() {
        show(withStatus(ProfileStatus(refreshing = true)))

        composeRule.onNodeWithTag(HomeTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(ProblemsTags.ALL_CLEAR).assertDoesNotExist()
    }

    @Test
    fun `a first failure with nothing to fall back on shows the reason and a way out`() {
        show(withStatus(ProfileStatus(error = StatusError.Nagios(NagiosError.BadCredentials))))

        composeRule.onNodeWithTag(HomeTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("rejected the username or password", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithText("Edit this profile").performClick()
        assertEquals(listOf("refresh", "edit:1"), calls)
    }

    @Test
    fun `unreadable credentials get their own explanation`() {
        show(withStatus(ProfileStatus(error = StatusError.CredentialsUnavailable)))

        composeRule.onNodeWithText("can no longer be read", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no problems at all reads as all clear, with the time of the last update`() {
        show(withReport(ProfileStatus(report = report(), lastSuccess = T0)))

        composeRule.onNodeWithTag(ProblemsTags.ALL_CLEAR).assertIsDisplayed()
        composeRule.onNodeWithText("No problems. Everything is OK.").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.UPDATED).assertIsDisplayed()
    }

    @Test
    fun `when everything left is handled it says so rather than all clear`() {
        val acked = CheckStatus(acknowledged = true, lastStateChange = T0)
        val status =
            ProfileStatus(
                report = report(services = listOf(service("Disk /", ServiceState.CRITICAL, acked))),
                lastSuccess = T0,
            )

        show(withReport(status))

        composeRule.onNodeWithText("No unhandled problems", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProblemsTags.HANDLED_TOGGLE).assertIsDisplayed()
    }

    @Test
    fun `unhandled problems are listed with severity, age, output and notes`() {
        val soft = CheckStatus(
            stateType = StateType.SOFT,
            currentAttempt = 2,
            maxAttempts = 3,
            pluginOutput = "CRITICAL - value 97",
            lastStateChange = T0 - Duration.ofMinutes(124),
            checksEnabled = false,
        )
        show(
            withReport(
                ProfileStatus(
                    report = report(services = listOf(service("Disk /", ServiceState.CRITICAL, soft))),
                    lastSuccess = T0,
                ),
            ),
        )

        composeRule.onNodeWithText("web01 / Disk /").assertIsDisplayed()
        composeRule.onNodeWithText("CRIT").assertIsDisplayed()
        composeRule.onNodeWithText("2h 04m").assertIsDisplayed()
        composeRule.onNodeWithText("CRITICAL - value 97").assertIsDisplayed()
        composeRule.onNodeWithText("soft, attempt 2 of 3 · checks off").assertIsDisplayed()
    }

    @Test
    fun `services on a down host appear under the host and the chips count the host only`() {
        val down = HostStatus("db01", HostState.DOWN, CheckStatus(pluginOutput = "HOST DOWN", lastStateChange = T0))
        val status = ProfileStatus(
            report = report(
                hosts = listOf(down),
                services = listOf(service("MySQL", ServiceState.CRITICAL, CheckStatus(), host = "db01")),
            ),
            lastSuccess = T0,
        )

        show(withReport(status))

        composeRule.onNodeWithText("db01").assertIsDisplayed()
        composeRule.onNodeWithText("MySQL").assertIsDisplayed()
        composeRule.onNodeWithText("1 host down").assertIsDisplayed()
        composeRule.onNodeWithText("Critical (0)").assertIsDisplayed()
    }

    @Test
    fun `the handled section is collapsed until asked for`() {
        val acked = CheckStatus(acknowledged = true, lastStateChange = T0)
        show(
            withReport(
                ProfileStatus(
                    report = report(services = listOf(service("Disk /", ServiceState.CRITICAL, acked))),
                    lastSuccess = T0,
                ),
            ),
        )

        composeRule.onNodeWithText("web01 / Disk /").assertDoesNotExist()
        composeRule.onNodeWithTag(ProblemsTags.HANDLED_TOGGLE).performScrollTo().performClick()

        composeRule.onNodeWithText("web01 / Disk /").assertIsDisplayed()
        composeRule.onNodeWithText("acknowledged").assertIsDisplayed()
        composeRule.onNodeWithText("Hide handled (1)").assertIsDisplayed()
    }

    // Real text measurement: with the default stub fonts every chip is a few dp wide, all four
    // fit on one row, and this test could not see the bug it exists for.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
    fun `all four count chips fit on a phone, each on one line`() {
        // The bug this guards against: in a single row the fourth chip was squeezed to nothing
        // and its label wrapped a letter per line, pushing the list far down the screen.
        show(withReport(ProfileStatus(report = report(), lastSuccess = T0)))

        val screenWidth = composeRule.onRoot().getUnclippedBoundsInRoot().width
        ProblemKind.entries.forEach { kind ->
            val bounds = composeRule.onNodeWithTag(
                ProblemsTags.chip(kind),
            ).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$kind chip is squeezed: ${bounds.width} wide", bounds.width > 60.dp)
            assertTrue("$kind chip wraps: ${bounds.height} tall", bounds.height < 64.dp)
            assertTrue("$kind chip runs off the screen", bounds.right <= screenWidth)
        }
        val updated = composeRule.onNodeWithTag(HomeTags.UPDATED).getUnclippedBoundsInRoot()
        assertTrue("the header is a sensible height, not stretched: ${updated.top}", updated.top < 320.dp)
    }

    @Test
    fun `tapping a chip asks to filter by it`() {
        show(
            withReport(
                ProfileStatus(
                    report = report(services = listOf(service("Disk /", ServiceState.WARNING, CheckStatus()))),
                    lastSuccess = T0,
                ),
            ),
        )

        composeRule.onNodeWithText("Warning (1)").performClick()

        assertEquals(listOf("filter:WARNING"), calls)
    }

    @Test
    fun `a filter that matches nothing says so`() {
        val base =
            withReport(
                ProfileStatus(
                    report = report(services = listOf(service("Disk /", ServiceState.WARNING, CheckStatus()))),
                    lastSuccess = T0,
                ),
            )

        show(base.copy(filter = setOf(ProblemKind.CRITICAL)))

        composeRule.onNodeWithText("Nothing matches this filter.").assertIsDisplayed()
    }

    @Test
    fun `a failed refresh keeps the old list and says it may be out of date`() {
        val status = ProfileStatus(
            report = report(
                services = listOf(service("Disk /", ServiceState.CRITICAL, CheckStatus(lastStateChange = T0))),
            ),
            lastSuccess = T0,
            error = StatusError.Nagios(NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "x")),
        )

        show(withReport(status))

        composeRule.onNodeWithTag(HomeTags.BANNER_FAILED).assertIsDisplayed()
        composeRule.onNodeWithText("did not answer in time", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("web01 / Disk /").assertIsDisplayed()
    }

    @Test
    fun `old data is labelled stale and never passed off as current`() {
        val status = ProfileStatus(report = report(), lastSuccess = T0)

        show(withReport(status).copy(now = T0 + Duration.ofMinutes(95)))

        composeRule.onNodeWithTag(HomeTags.BANNER_STALE).assertIsDisplayed()
        composeRule.onNodeWithText("1h 35m old", substring = true).assertIsDisplayed()
    }

    @Test
    fun `fresh data carries no stale banner`() {
        show(withReport(ProfileStatus(report = report(), lastSuccess = T0)).copy(now = T0 + Duration.ofMinutes(5)))

        composeRule.onNodeWithTag(HomeTags.BANNER_STALE).assertDoesNotExist()
        composeRule.onNodeWithTag(HomeTags.BANNER_FAILED).assertDoesNotExist()
    }

    @Test
    fun `degraded records are called out and listed with their state only`() {
        val degraded = CheckStatus(detailsAvailable = false)
        show(
            withReport(
                ProfileStatus(
                    report = report(services = listOf(service("Odd", ServiceState.CRITICAL, degraded))),
                    lastSuccess = T0,
                ),
            ),
        )

        composeRule.onNodeWithTag(HomeTags.BANNER_DEGRADED).assertIsDisplayed()
        composeRule.onNodeWithText("details unavailable", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("web01 / Odd").assertIsDisplayed()
    }

    @Test
    fun `the profile menu switches profile and reaches editing and management`() {
        show(
            withReport(
                ProfileStatus(report = report(), lastSuccess = T0),
            ).copy(profiles = listOf(profile(1, "Home"), profile(2, "Office"))),
        )

        composeRule.onNodeWithTag(HomeTags.PROFILE_MENU).performClick()
        composeRule.onNodeWithText("Office").performClick()
        composeRule.onNodeWithTag(HomeTags.PROFILE_MENU).performClick()
        composeRule.onNodeWithText("Edit this profile").performClick()
        composeRule.onNodeWithTag(HomeTags.PROFILE_MENU).performClick()
        composeRule.onNodeWithText("Manage profiles").performClick()

        assertEquals(listOf("select:2", "edit:1", "manage"), calls)
    }

    private fun show(initial: HomeUiState) {
        state = initial
        composeRule.setContent { NagwatchTheme(dynamicColor = false) { HomeContent(state, actions) } }
    }

    private fun withStatus(status: ProfileStatus) = HomeUiState(
        loading = false,
        profiles = listOf(profile(1, "Home")),
        selected = profile(1, "Home"),
        status = status,
        now = T0,
    )

    private fun withReport(status: ProfileStatus) = withStatus(status)

    private fun report(hosts: List<HostStatus> = emptyList(), services: List<ServiceStatus> = emptyList()) =
        ProblemClassifier.classify(
            snapshot(hosts = listOf(HostStatus("web01", HostState.UP, CheckStatus())) + hosts, services = services),
        )

    private fun service(name: String, state: ServiceState, check: CheckStatus, host: String = "web01") =
        ServiceStatus(host, name, state, check)

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
