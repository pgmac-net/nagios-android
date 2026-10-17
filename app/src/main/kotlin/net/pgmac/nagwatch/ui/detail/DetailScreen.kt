// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.NagwatchTopAppBar
import net.pgmac.nagwatch.ui.browse.HostStyle
import net.pgmac.nagwatch.ui.browse.ServiceStyle
import net.pgmac.nagwatch.ui.graph.GraphActions
import net.pgmac.nagwatch.ui.graph.GraphSection
import net.pgmac.nagwatch.ui.graph.GraphViewModel
import net.pgmac.nagwatch.ui.home.Banner
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.message
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

/** What the detail screen can ask for. */
interface DetailActions : CommandActions {
    fun back()

    fun refresh()

    fun openHost(hostName: String)

    fun openService(hostName: String, description: String)

    /** Hands a page of the Nagios web interface to the browser. */
    fun openInNagios(url: String)
}

/** The commands the detail screen can send, and the questions that go with them. */
interface CommandActions {
    fun acknowledge(comment: String, sticky: Boolean, notify: Boolean, persistent: Boolean)

    fun removeAcknowledgement()

    fun addComment(comment: String)

    fun forceCheck()

    fun scheduleDowntime(comment: String, window: DowntimeWindow)

    fun cancelDowntime(id: Long)

    fun answerDateOrder(format: ServerDateFormat)

    fun dismissDateOrder()

    /** The downtime form has opened and would like to show the window as the server will read it. */
    fun loadServerClock()
}

/** Where the detail screen can send the user. */
interface DetailNavigation {
    fun back()

    fun openHost(profileId: Long, hostName: String)

    fun openService(profileId: Long, hostName: String, description: String)

    fun openInBrowser(url: String)

    /** The service's graph with the screen to itself. */
    fun openGraph(profileId: Long, hostName: String, description: String)
}

@Composable
fun DetailScreen(
    navigation: DetailNavigation,
    viewModel: DetailViewModel = hiltViewModel(),
    graphs: GraphViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph by graphs.state.collectAsStateWithLifecycle()
    val actions = object : DetailActions {
        override fun back() = navigation.back()

        // The graph is asked for again too, including one that was ruled out: a server can be fixed.
        override fun refresh() {
            viewModel.refresh()
            graphs.refresh()
        }

        override fun openHost(hostName: String) = navigation.openHost(viewModel.profileId, hostName)

        override fun openService(hostName: String, description: String) =
            navigation.openService(viewModel.profileId, hostName, description)

        override fun openInNagios(url: String) = navigation.openInBrowser(url)

        override fun acknowledge(comment: String, sticky: Boolean, notify: Boolean, persistent: Boolean) {
            viewModel.acknowledge(comment, sticky, notify, persistent)
        }

        override fun removeAcknowledgement() = viewModel.removeAcknowledgement()

        override fun addComment(comment: String) = viewModel.addComment(comment)

        override fun forceCheck() = viewModel.forceCheck()

        override fun scheduleDowntime(comment: String, window: DowntimeWindow) =
            viewModel.scheduleDowntime(comment, window)

        override fun cancelDowntime(id: Long) = viewModel.cancelDowntime(id)

        override fun answerDateOrder(format: ServerDateFormat) = viewModel.answerDateOrder(format)

        override fun dismissDateOrder() = viewModel.dismissDateOrder()

        override fun loadServerClock() = viewModel.loadServerClock()
    }
    val graphActions = object : GraphActions {
        override fun show(size: GraphSize) = graphs.show(size)

        override fun choose(period: GraphPeriod) = graphs.choose(period)

        override fun open() {
            viewModel.ref.description?.let { navigation.openGraph(viewModel.profileId, viewModel.ref.hostName, it) }
        }

        override fun openInBrowser(url: String) = navigation.openInBrowser(url)
    }
    DetailContent(state, actions) { GraphSection(graph, graphActions) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailContent(
    state: DetailUiState,
    actions: DetailActions,
    modifier: Modifier = Modifier,
    graph: @Composable () -> Unit = {},
) {
    var allComments by rememberSaveable { mutableStateOf(false) }
    // The id of the downtime being asked about, while "Cancel this downtime?" is up.
    var cancelling by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            NagwatchTopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(DetailTags.TITLE),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions::back, modifier = Modifier.testTag(DetailTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.detail.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag(DetailTags.LIST)) {
                item { Header(state) }
                item { Freshness(state, actions) }
                if (state.actionsOffered) item { Actions(state, actions) }
                // Draws nothing at all when there is no graph, which is most of the time for most services.
                item { graph() }
                val check = state.check
                if (check != null && check.detailsAvailable) {
                    item { Output(check) }
                    item { Flags(check) }
                }
                state.host?.let { host -> item { HostLink(host, actions) } }
                hostServices(state, actions)
                comments(state.detail.comments, allComments) { allComments = !allComments }
                downtimes(state.detail.downtimes, state.actionsEnabled) { cancelling = it }
                state.detail.links?.takeUnless { it.isEmpty }?.let { links -> item { Links(links, actions) } }
                state.detail.nagiosUrl?.let { url -> item { OpenInNagios(url, actions) } }
            }
        }
    }
    // Only while it is still listed: if a refresh shows it gone, there is nothing left to ask about.
    state.detail.downtimes.items.firstOrNull { it.id == cancelling }?.let { downtime ->
        CancelDowntimeDialog(downtime, onDismiss = { cancelling = null }) {
            cancelling = null
            actions.cancelDowntime(downtime.id)
        }
    }
    (state.action as? ActionProgress.DateOrderNeeded)?.let { DateOrderDialog(it, actions) }
}

/** State, how settled it is, how long for, and when it was and will be checked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: DetailUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(DetailTags.HEADER),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val check = state.check
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.state?.let { StateLabel(it) }
            if (check != null && check.detailsAvailable) {
                val type = if (check.stateType == StateType.SOFT) R.string.detail_soft else R.string.detail_hard
                Text(stringResource(type), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.detail_attempt, check.currentAttempt, check.maxAttempts),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (check != null && check.detailsAvailable) {
            check.lastStateChange?.let { since ->
                Text(
                    stringResource(R.string.detail_for, formatAge(since, state.now)),
                    modifier = Modifier.testTag(DetailTags.SINCE),
                )
            }
            Text(
                text = checkTimes(check),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (check?.detailsAvailable == false) {
            Text(stringResource(R.string.note_details_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StateLabel(state: Enum<*>) {
    val colors = NagwatchTheme.stateColors
    when (state) {
        is HostState -> StateBadge(HostStyle.label(state), HostStyle.color(state, colors))
        is ServiceState -> StateBadge(ServiceStyle.label(state), ServiceStyle.color(state, colors))
    }
}

/**
 * How current what is shown is, and what to do when nothing could be loaded.
 * A record from the cache or from the last poll is useful, and is never passed
 * off as fresh.
 */
@Composable
private fun Freshness(state: DetailUiState, actions: DetailActions) {
    val detail = state.detail
    val record = detail.record
    Column(Modifier.fillMaxWidth()) {
        if (record != null) {
            Text(
                text = stringResource(R.string.detail_as_of, formatWhen(record.fetchedAt)),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(DetailTags.AS_OF),
            )
        }
        val error = detail.error
        when {
            error != null && record != null ->
                Banner(stringResource(R.string.problems_banner_failed, error.message()), DetailTags.BANNER_FAILED)

            error != null -> NothingLoaded(error, actions)

            record == null -> Text(
                stringResource(R.string.problems_loading),
                modifier = Modifier.padding(16.dp).testTag(DetailTags.LOADING),
            )
        }
        if (record != null && !detail.refreshing &&
            Duration.between(record.fetchedAt, state.now) > state.staleAfter
        ) {
            Banner(
                stringResource(R.string.problems_banner_stale, formatAge(record.fetchedAt, state.now)),
                DetailTags.BANNER_STALE,
            )
        }
    }
}

/** Nothing saved and nothing fetched: say why, and offer to try again. */
@Composable
private fun NothingLoaded(error: StatusError, actions: DetailActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(DetailTags.ERROR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.detail_failed_title), style = MaterialTheme.typography.titleMedium)
        Text(error.message())
        Button(onClick = actions::refresh) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
internal fun Section(title: Int, modifier: Modifier = Modifier, count: Int? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        val heading = stringResource(title)
        SectionTitle(if (count == null) heading else stringResource(R.string.detail_counted, heading, count))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
