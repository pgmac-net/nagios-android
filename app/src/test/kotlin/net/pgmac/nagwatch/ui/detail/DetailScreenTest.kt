// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.status.AnnotationSection
import net.pgmac.nagwatch.status.ObjectDetail
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.comment
import net.pgmac.nagwatch.status.downtime
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The detail screen in each state it can be in. The screen is tall here so
 * that every section is on it at once; scrolling is the list's business.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, qualifiers = "w360dp-h3000dp")
class DetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(DetailUiState(ObjectDetail(DISK_REF)))
    private val calls = mutableListOf<String>()
    private val actions = object : DetailActions {
        override fun back() {
            calls += "back"
        }

        override fun refresh() {
            calls += "refresh"
        }

        override fun openHost(hostName: String) {
            calls += "host:$hostName"
        }

        override fun openService(hostName: String, description: String) {
            calls += "service:$hostName/$description"
        }

        override fun openInNagios(url: String) {
            calls += "open:$url"
        }
    }

    private val hard = CheckStatus(
        stateType = StateType.HARD,
        pluginOutput = "DISK CRITICAL - 97% used",
        longOutput = "/var 97%\n/home 12%",
        perfData = "/var=97%;80;90",
        currentAttempt = 3,
        maxAttempts = 3,
        lastCheck = T0.minusSeconds(60),
        nextCheck = T0.plusSeconds(240),
        lastStateChange = T0.minus(Duration.ofMinutes(124)),
    )

    @Test
    fun `the title names the service with its host, and back goes back`() {
        show(service(hard))

        composeRule.onNodeWithTag(DetailTags.TITLE).assertTextEquals("web01 / Disk /")
        composeRule.onNodeWithTag(DetailTags.BACK).performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun `the header gives state, how settled it is, attempts, how long for, and the check times`() {
        show(service(hard))

        inHeader("CRIT").assertIsDisplayed()
        inHeader("HARD").assertIsDisplayed()
        inHeader("attempt 3 of 3").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.SINCE).assertTextEquals("In this state for 2h 04m")
        composeRule.onNodeWithText("last check", substring = true).assertTextContains("active check", substring = true)
        composeRule.onNodeWithText("next", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a problem still being retried says soft, and a passive check says passive`() {
        show(service(hard.copy(stateType = StateType.SOFT, currentAttempt = 1, activeCheck = false)))

        inHeader("SOFT").assertIsDisplayed()
        inHeader("attempt 1 of 3").assertIsDisplayed()
        composeRule.onNodeWithText("passive check", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the output is shown, the long output and performance data on request`() {
        show(service(hard))

        composeRule.onNodeWithTag(DetailTags.OUTPUT).assertTextEquals("DISK CRITICAL - 97% used")
        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.PERF).assertDoesNotExist()

        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT_TOGGLE).performClick()
        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT).assertTextContains("/home 12%", substring = true)
        composeRule.onNodeWithTag(DetailTags.PERF_TOGGLE).performClick()
        composeRule.onNodeWithTag(DetailTags.PERF).assertTextEquals("/var=97%;80;90")

        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT_TOGGLE).performClick()
        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT).assertDoesNotExist()
    }

    @Test
    fun `with no long output or performance data there is nothing to expand`() {
        show(service(CheckStatus(pluginOutput = "OK")))

        composeRule.onNodeWithTag(DetailTags.LONG_OUTPUT_TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.PERF_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun `a check that printed nothing says so rather than leaving a gap`() {
        show(service(CheckStatus()))

        composeRule.onNodeWithTag(DetailTags.OUTPUT).assertTextEquals("The check returned no output.")
    }

    @Test
    fun `the status section spells out each switch`() {
        val check = hard.copy(acknowledged = true, downtimeDepth = 1, notificationsEnabled = false, flapping = true)
        show(service(check))

        listOf(
            "Acknowledged: yes",
            "In scheduled downtime: yes",
            "Active checks: enabled",
            "Notifications: disabled",
            "Flapping: yes",
        ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `a record Nagios could not read says so, and claims nothing it does not know`() {
        show(service(CheckStatus(detailsAvailable = false)))

        composeRule.onNodeWithText("details unavailable", substring = true).assertIsDisplayed()
        inHeader("CRIT").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.OUTPUT).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.FLAGS).assertDoesNotExist()
        composeRule.onAllNodesWithText("HARD").assertCountEquals(0)
    }

    @Test
    fun `while the first load is under way it says loading`() {
        show(DetailUiState(ObjectDetail(DISK_REF, refreshing = true)))

        composeRule.onNodeWithTag(DetailTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `a service with nothing loaded yet still shows the state the last poll gave it`() {
        show(DetailUiState(ObjectDetail(DISK_REF, refreshing = true), polledState = ServiceState.WARNING))

        inHeader("WARN").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun `when nothing could be loaded it gives the reason and a way to try again`() {
        show(DetailUiState(ObjectDetail(DISK_REF, error = StatusError.Nagios(NagiosError.BadCredentials))))

        composeRule.onNodeWithTag(DetailTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("rejected the username or password", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun `a failed refresh keeps the record, says when it is from and why it is not newer`() {
        val detail = service(hard).detail.copy(error = StatusError.Nagios(NagiosError.Http(502)))
        show(DetailUiState(detail, now = T0))

        composeRule.onNodeWithTag(DetailTags.OUTPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.AS_OF).assertTextContains("As of", substring = true)
        composeRule.onNodeWithTag(DetailTags.BANNER_FAILED).assertTextContains("HTTP 502", substring = true)
        composeRule.onNodeWithTag(DetailTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `an old record is labelled with its age, but not while it is being refreshed`() {
        val old = service(hard).copy(now = T0.plus(Duration.ofHours(3)))
        show(old)

        composeRule.onNodeWithTag(DetailTags.BANNER_STALE).assertTextContains("3h 00m old", substring = true)

        state = old.copy(detail = old.detail.copy(refreshing = true))

        composeRule.onNodeWithTag(DetailTags.BANNER_STALE).assertDoesNotExist()
    }

    @Test
    fun `a service links to its host, with the host's state`() {
        show(service(hard))

        composeRule.onNodeWithTag(DetailTags.HOST_LINK).assertTextContains("web01").assertTextContains("UP")
        composeRule.onNodeWithTag(DetailTags.HOST_LINK).performClick()
        assertEquals(listOf("host:web01"), calls)
    }

    @Test
    fun `a host lists its services worst first, each opening that service`() {
        show(host())

        composeRule.onNodeWithTag(DetailTags.TITLE).assertTextEquals("web01")
        composeRule.onNodeWithText("3 services").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.HOST_LINK).assertDoesNotExist()
        val disk = composeRule.onNodeWithTag(DetailTags.service("Disk /")).getUnclippedBoundsInRoot()
        val ping = composeRule.onNodeWithTag(DetailTags.service("Ping")).getUnclippedBoundsInRoot()
        assertTrue("the critical one is above the one that is fine", disk.top < ping.top)

        composeRule.onNodeWithTag(DetailTags.service("Load")).performClick()
        assertEquals(listOf("service:web01/Load"), calls)
    }

    @Test
    fun `a host with no services says so in its count`() {
        show(host().copy(services = emptyList()))

        composeRule.onNodeWithText("0 services").assertIsDisplayed()
    }

    @Test
    fun `comments say who, what kind and when, newest first`() {
        val comments = listOf(
            comment(2, "disk replaced", T0, Comment.Kind.USER, "alice"),
            comment(1, "looking at it", T0.minusSeconds(3600), Comment.Kind.ACKNOWLEDGEMENT, "bob"),
        )
        show(service(hard, comments = AnnotationSection(comments, T0)))

        composeRule.onNodeWithText("Comments (2)").assertIsDisplayed()
        composeRule.onNodeWithText("alice · comment", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("bob · acknowledgement", substring = true).assertIsDisplayed()
        val newer = composeRule.onNodeWithText("disk replaced").getUnclippedBoundsInRoot()
        val older = composeRule.onNodeWithText("looking at it").getUnclippedBoundsInRoot()
        assertTrue(newer.top < older.top)
    }

    @Test
    fun `only the first twenty comments are shown until all are asked for`() {
        val comments = (30L downTo 1L).map { comment(it, "note number $it") }
        show(service(hard, comments = AnnotationSection(comments, T0)))

        composeRule.onNodeWithText("note number 11").assertIsDisplayed()
        composeRule.onNodeWithText("note number 10").assertDoesNotExist()

        composeRule.onNodeWithTag(DetailTags.COMMENTS_ALL).assertTextContains("Show all 30").performClick()

        composeRule.onNodeWithTag(DetailTags.LIST).performScrollToNode(hasText("note number 1"))
        composeRule.onNodeWithText("note number 1").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.LIST).performScrollToNode(hasTestTag(DetailTags.COMMENTS_ALL))
        composeRule.onNodeWithTag(DetailTags.COMMENTS_ALL).assertTextContains("Show fewer")
    }

    @Test
    fun `no comments and no downtime each say so`() {
        show(service(hard))

        composeRule.onNodeWithText("No comments.").assertIsDisplayed()
        composeRule.onNodeWithText("No downtime is scheduled.").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.COMMENTS_ALL).assertDoesNotExist()
    }

    @Test
    fun `while comments are loading it does not claim there are none`() {
        show(service(hard, comments = AnnotationSection(loading = true)))

        composeRule.onNodeWithText("No comments.").assertDoesNotExist()
    }

    @Test
    fun `comments being unavailable is said in their section only, and the rest of the screen stands`() {
        val failed = AnnotationSection<Comment>(error = StatusError.Nagios(NagiosError.Forbidden))
        show(service(hard, comments = failed, downtimes = AnnotationSection(listOf(downtime(7)), T0)))

        composeRule.onNode(hasText("Comments are unavailable", substring = true))
            .assertTextContains("refused access", substring = true)
        composeRule.onNodeWithText("No comments.").assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.OUTPUT).assertIsDisplayed()
        composeRule.onNodeWithText("maintenance 7").assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.BANNER_FAILED).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `comments saved on an earlier visit still show when they cannot be refreshed`() {
        val stale = AnnotationSection(
            items = listOf(comment(1, "from last week")),
            fetchedAt = T0,
            error = StatusError.Nagios(NagiosError.Http(500)),
        )
        show(service(hard, comments = stale))

        composeRule.onNodeWithText("Comments are unavailable", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Showing what was saved", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("from last week").assertIsDisplayed()
    }

    @Test
    fun `a fixed downtime gives its window, and one in effect says so`() {
        show(service(hard, downtimes = AnnotationSection(listOf(downtime(7, inEffect = true)), T0)))

        composeRule.onNodeWithText("Scheduled downtime (1)").assertIsDisplayed()
        composeRule.onNodeWithText("bob · ", substring = true)
            .assertTextContains(" to ", substring = true)
            .assertTextContains("fixed", substring = true)
            .assertTextContains("in effect now", substring = true)
        composeRule.onNodeWithText("maintenance 7").assertIsDisplayed()
    }

    @Test
    fun `a flexible downtime says how long it lasts once it starts`() {
        show(service(hard, downtimes = AnnotationSection(listOf(downtime(7, fixed = false)), T0)))

        composeRule.onNodeWithText("flexible, lasting 2h 00m", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("in effect now", substring = true).assertDoesNotExist()
    }

    @Test
    fun `downtimes being unavailable is said in their section only`() {
        val failed = AnnotationSection<net.pgmac.nagwatch.nagios.model.Downtime>(
            error = StatusError.Nagios(NagiosError.Api(8, "error", "no such query")),
        )
        show(service(hard, comments = AnnotationSection(listOf(comment(1)), T0), downtimes = failed))

        composeRule.onNodeWithText("Downtimes are unavailable", substring = true)
            .assertTextContains("no such query", substring = true)
        composeRule.onNodeWithText("note 1").assertIsDisplayed()
        composeRule.onNodeWithText("No downtime is scheduled.").assertDoesNotExist()
    }

    @Test
    fun `Open in Nagios hands over the page it was given, and is absent when there is none`() {
        val url = "https://nagios.example.org/nagios/cgi-bin/extinfo.cgi?type=2&host=web01&service=Disk%20%2F"
        show(service(hard).let { it.copy(detail = it.detail.copy(nagiosUrl = url)) })

        composeRule.onNodeWithTag(DetailTags.OPEN_IN_NAGIOS).performClick()
        assertEquals(listOf("open:$url"), calls)

        state = service(hard)
        composeRule.onNodeWithTag(DetailTags.OPEN_IN_NAGIOS).assertDoesNotExist()
    }

    // Real text measurement, at phone width: a long name must not push the header off the screen.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(application = android.app.Application::class, qualifiers = "w360dp-h3000dp-xhdpi")
    fun `the header and a long title stay inside a phone's width`() {
        val longRef = ObjectRef("a-rather-long-host-name.example.org", "A service with a long descriptive name")
        val record = CachedDetail.Service(
            ServiceStatus(longRef.hostName, longRef.description.orEmpty(), ServiceState.UNKNOWN, hard),
            T0,
        )
        show(DetailUiState(ObjectDetail(longRef, record = record), now = T0))

        val screen = composeRule.onRoot().getUnclippedBoundsInRoot().width
        listOf(DetailTags.TITLE, DetailTags.HEADER, DetailTags.SINCE, DetailTags.OUTPUT).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag runs off the screen: ${bounds.right} of $screen", bounds.right <= screen)
            assertTrue("$tag has collapsed: ${bounds.width}", bounds.width > 40.dp)
        }
    }

    private fun inHeader(text: String) =
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(DetailTags.HEADER)))

    private fun show(initial: DetailUiState) {
        state = initial
        composeRule.setContent { NagwatchTheme(dynamicColor = false) { DetailContent(state, actions) } }
    }

    private fun service(
        check: CheckStatus,
        comments: AnnotationSection<Comment> = AnnotationSection(fetchedAt = T0),
        downtimes: AnnotationSection<net.pgmac.nagwatch.nagios.model.Downtime> = AnnotationSection(fetchedAt = T0),
    ): DetailUiState {
        val record = CachedDetail.Service(ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, check), T0)
        return detailUiState(
            ObjectDetail(DISK_REF, record = record, comments = comments, downtimes = downtimes),
            status(),
            T0,
        )
    }

    private fun host(): DetailUiState {
        val record = CachedDetail.Host(HostStatus("web01", HostState.UP, hard), T0)
        return detailUiState(ObjectDetail(ObjectRef("web01"), record = record), status(), T0)
    }

    private fun status(): ProfileStatus {
        val snapshot = StatusSnapshot(
            hosts = listOf(HostStatus("web01", HostState.UP, CheckStatus())),
            serviceProblems = listOf(
                ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, hard),
                ServiceStatus("web01", "Load", ServiceState.WARNING, CheckStatus(acknowledged = true)),
            ),
            fetchedAt = T0,
            serviceStates = listOf(
                ServiceStateEntry("web01", "Ping", ServiceState.OK),
                ServiceStateEntry("web01", "Disk /", ServiceState.CRITICAL),
                ServiceStateEntry("web01", "Load", ServiceState.WARNING),
            ),
        )
        return ProfileStatus(report = ProblemClassifier.classify(snapshot), snapshot = snapshot, lastSuccess = T0)
    }

    private companion object {
        val DISK_REF = ObjectRef("web01", "Disk /")
    }
}
