// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.ZoneOffset
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
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
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.AnnotationSection
import net.pgmac.nagwatch.status.CommandPermission
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
 * What the detail screen tests share: the screen, a recorder for what it asks for, and a
 * few states to show. The screen is tall here so that every section is on it at once;
 * scrolling is the list's business.
 */
abstract class DetailScreenFixture {
    @get:Rule
    val composeRule = createComposeRule()

    protected var state by mutableStateOf(DetailUiState(ObjectDetail(DISK_REF)))
    protected val calls = mutableListOf<String>()
    protected val actions = object : DetailActions {
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

        override fun acknowledge(comment: String, sticky: Boolean, notify: Boolean, persistent: Boolean) {
            calls += "acknowledge:$comment|sticky=$sticky|notify=$notify|persistent=$persistent"
        }

        override fun removeAcknowledgement() {
            calls += "removeAcknowledgement"
        }

        override fun addComment(comment: String) {
            calls += "comment:$comment"
        }

        override fun forceCheck() {
            calls += "recheck"
        }

        override fun scheduleDowntime(comment: String, window: DowntimeWindow) {
            calls += "downtime:$comment|$window"
        }

        override fun cancelDowntime(id: Long) {
            calls += "cancelDowntime:$id"
        }

        override fun answerDateOrder(format: ServerDateFormat) {
            calls += "dateOrder:$format"
        }

        override fun dismissDateOrder() {
            calls += "dismissDateOrder"
        }

        override fun loadServerClock() {
            calls += "loadServerClock"
        }
    }

    protected val hard = CheckStatus(
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

    protected fun show(initial: DetailUiState) {
        state = initial
        composeRule.setContent { NagwatchTheme(dynamicColor = false) { DetailContent(state, actions) } }
    }

    protected fun service(
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

    protected fun host(): DetailUiState {
        val record = CachedDetail.Host(HostStatus("web01", HostState.UP, hard), T0)
        return detailUiState(ObjectDetail(ObjectRef("web01"), record = record), status(), T0)
    }

    protected fun status(): ProfileStatus {
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
}

internal val DISK_REF = ObjectRef("web01", "Disk /")
