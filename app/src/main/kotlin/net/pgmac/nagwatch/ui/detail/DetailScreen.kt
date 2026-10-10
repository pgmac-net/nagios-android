// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.StateType
import net.pgmac.nagwatch.status.AnnotationSection
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.browse.BrowseRow
import net.pgmac.nagwatch.ui.browse.HostStyle
import net.pgmac.nagwatch.ui.browse.ServiceStyle
import net.pgmac.nagwatch.ui.home.Banner
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.checkNotes
import net.pgmac.nagwatch.ui.home.message
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

/** What the detail screen can ask for. */
interface DetailActions {
    fun back()

    fun refresh()

    fun openHost(hostName: String)

    fun openService(hostName: String, description: String)

    /** Hands a page of the Nagios web interface to the browser. */
    fun openInNagios(url: String)
}

/** Where the detail screen can send the user. */
interface DetailNavigation {
    fun back()

    fun openHost(profileId: Long, hostName: String)

    fun openService(profileId: Long, hostName: String, description: String)

    fun openInBrowser(url: String)
}

@Composable
fun DetailScreen(navigation: DetailNavigation, viewModel: DetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = object : DetailActions {
        override fun back() = navigation.back()

        override fun refresh() = viewModel.refresh()

        override fun openHost(hostName: String) = navigation.openHost(viewModel.profileId, hostName)

        override fun openService(hostName: String, description: String) =
            navigation.openService(viewModel.profileId, hostName, description)

        override fun openInNagios(url: String) = navigation.openInBrowser(url)
    }
    DetailContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailContent(state: DetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
    var allComments by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
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
                val check = state.check
                if (check != null && check.detailsAvailable) {
                    item { Output(check) }
                    item { Flags(check) }
                }
                state.host?.let { host -> item { HostLink(host, actions) } }
                hostServices(state, actions)
                comments(state.detail.comments, allComments) { allComments = !allComments }
                downtimes(state.detail.downtimes)
                state.detail.nagiosUrl?.let { url -> item { OpenInNagios(url, actions) } }
            }
        }
    }
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

@Composable
private fun checkTimes(check: CheckStatus): String {
    val parts = buildList {
        check.lastCheck?.let { add(stringResource(R.string.detail_last_check, formatWhen(it))) }
        check.nextCheck?.let { add(stringResource(R.string.detail_next_check, formatWhen(it))) }
        add(stringResource(if (check.activeCheck) R.string.detail_active else R.string.detail_passive))
    }
    return parts.joinToString(" · ")
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
            Duration.between(record.fetchedAt, state.now) > ProfileStatus.STALE_AFTER
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

/** What the plugin said. Selectable, because the next step is often to paste it somewhere. */
@Composable
private fun Output(check: CheckStatus) {
    var longOpen by rememberSaveable { mutableStateOf(false) }
    var perfOpen by rememberSaveable { mutableStateOf(false) }
    Section(R.string.detail_output) {
        SelectionContainer {
            Text(
                text = check.pluginOutput.ifBlank { stringResource(R.string.detail_no_output) },
                modifier = Modifier.testTag(DetailTags.OUTPUT),
            )
        }
        if (check.longOutput.isNotBlank()) {
            if (longOpen) {
                SelectionContainer {
                    Text(
                        text = check.longOutput,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(DetailTags.LONG_OUTPUT),
                    )
                }
            }
            TextButton(onClick = { longOpen = !longOpen }, modifier = Modifier.testTag(DetailTags.LONG_OUTPUT_TOGGLE)) {
                Text(stringResource(if (longOpen) R.string.detail_long_hide else R.string.detail_long_show))
            }
        }
        if (check.perfData.isNotBlank()) {
            TextButton(onClick = { perfOpen = !perfOpen }, modifier = Modifier.testTag(DetailTags.PERF_TOGGLE)) {
                Text(stringResource(if (perfOpen) R.string.detail_perf_hide else R.string.detail_perf_show))
            }
            if (perfOpen) {
                SelectionContainer {
                    // As Nagios stores it. It becomes graphs later; until then it is at least readable.
                    Text(
                        text = check.perfData,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag(DetailTags.PERF),
                    )
                }
            }
        }
    }
}

/** The switches that change what a state means, each spelled out. */
@Composable
private fun Flags(check: CheckStatus) {
    val yes = stringResource(R.string.detail_yes)
    val no = stringResource(R.string.detail_no)
    val on = stringResource(R.string.detail_enabled)
    val off = stringResource(R.string.detail_disabled)
    Section(R.string.detail_flags, Modifier.testTag(DetailTags.FLAGS)) {
        Fact(R.string.detail_flag_acknowledged, if (check.acknowledged) yes else no)
        Fact(R.string.detail_flag_downtime, if (check.inDowntime) yes else no)
        Fact(R.string.detail_flag_checks, if (check.checksEnabled) on else off)
        Fact(R.string.detail_flag_notifications, if (check.notificationsEnabled) on else off)
        Fact(R.string.detail_flag_flapping, if (check.flapping) yes else no)
    }
}

@Composable
private fun Fact(label: Int, value: String) {
    Text(
        stringResource(R.string.detail_fact, stringResource(label), value),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun HostLink(host: BrowseRow<HostState>, actions: DetailActions) {
    Section(R.string.detail_host) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.openHost(host.hostName) }
                .padding(vertical = 8.dp)
                .testTag(DetailTags.HOST_LINK),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateBadge(HostStyle.label(host.state), HostStyle.color(host.state, NagwatchTheme.stateColors))
            Text(host.hostName, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** A host's services, worst first, each a way in to that service. */
private fun LazyListScope.hostServices(state: DetailUiState, actions: DetailActions) {
    if (!state.ref.isHost) return
    item {
        SectionTitle(pluralStringResource(R.plurals.detail_services, state.services.size, state.services.size))
    }
    items(state.services, key = { "service:${it.key}" }) { row ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.openService(row.hostName, row.service.orEmpty()) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(DetailTags.service(row.service.orEmpty())),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateBadge(ServiceStyle.label(row.state), ServiceStyle.color(row.state, NagwatchTheme.stateColors))
                Text(
                    text = row.service.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val notes = checkNotes(row.markers, row.handling, row.check)
            if (notes.isNotEmpty()) {
                Text(
                    notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}

/** Newest first, each its own row: a busy object can have a couple of hundred. */
private fun LazyListScope.comments(section: AnnotationSection<Comment>, all: Boolean, onToggleAll: () -> Unit) {
    item {
        AnnotationHeader(
            title = R.string.detail_comments,
            unavailable = R.string.detail_comments_unavailable,
            none = R.string.detail_comments_none,
            tag = DetailTags.COMMENTS,
            section = section,
        )
    }
    val shown = if (all) section.items else section.items.take(COMMENTS_SHOWN)
    items(shown, key = { "comment:${it.id}" }) { comment ->
        Annotation(
            heading = listOf(comment.author, stringResource(comment.kind.label()), formatWhen(comment.enteredAt))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            text = comment.text,
        )
    }
    if (section.items.size > COMMENTS_SHOWN) {
        item {
            TextButton(
                onClick = onToggleAll,
                modifier = Modifier.padding(horizontal = 4.dp).testTag(DetailTags.COMMENTS_ALL),
            ) {
                val label = if (all) R.string.detail_comments_fewer else R.string.detail_comments_all
                Text(stringResource(label, section.items.size))
            }
        }
    }
}

private fun LazyListScope.downtimes(section: AnnotationSection<Downtime>) {
    item {
        AnnotationHeader(
            title = R.string.detail_downtimes,
            unavailable = R.string.detail_downtimes_unavailable,
            none = R.string.detail_downtimes_none,
            tag = DetailTags.DOWNTIMES,
            section = section,
        )
    }
    items(section.items, key = { "downtime:${it.id}" }) { downtime ->
        val window =
            stringResource(R.string.detail_downtime_window, formatWhen(downtime.start), formatWhen(downtime.end))
        val kind = if (downtime.fixed) {
            stringResource(R.string.detail_downtime_fixed)
        } else {
            stringResource(R.string.detail_downtime_flexible, formatSpan(downtime.duration))
        }
        val now = if (downtime.inEffect) stringResource(R.string.detail_downtime_in_effect) else ""
        Annotation(
            heading = listOf(downtime.author, window, kind, now).filter { it.isNotBlank() }.joinToString(" · "),
            text = downtime.comment,
        )
    }
}

/**
 * The heading of the comments or the downtimes, and whichever of their states
 * applies. When they could not be fetched the reason is given here and only
 * here: the rest of the screen is unaffected, and anything saved from an
 * earlier visit still shows beneath.
 */
@Composable
private fun AnnotationHeader(title: Int, unavailable: Int, none: Int, tag: String, section: AnnotationSection<*>) {
    val count = section.items.size
    val error = section.error
    Section(title, Modifier.testTag(tag), count = count.takeIf { it > 0 }) {
        when {
            error != null -> {
                Text(
                    text = stringResource(unavailable, error.message()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (count > 0) {
                    Text(
                        text = stringResource(R.string.detail_saved_earlier, formatWhen(section.fetchedAt)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            section.loading && count == 0 ->
                Text(stringResource(R.string.problems_loading), style = MaterialTheme.typography.bodySmall)

            count == 0 -> Text(stringResource(none), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Annotation(heading: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun OpenInNagios(url: String, actions: DetailActions) {
    OutlinedButton(
        onClick = { actions.openInNagios(url) },
        modifier = Modifier.padding(16.dp).testTag(DetailTags.OPEN_IN_NAGIOS),
    ) {
        Text(stringResource(R.string.detail_open_in_nagios))
    }
}

@Composable
private fun Section(title: Int, modifier: Modifier = Modifier, count: Int? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        val heading = stringResource(title)
        SectionTitle(if (count == null) heading else stringResource(R.string.detail_counted, heading, count))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun Comment.Kind.label(): Int = when (this) {
    Comment.Kind.USER -> R.string.detail_comment_user
    Comment.Kind.ACKNOWLEDGEMENT -> R.string.detail_comment_acknowledgement
    Comment.Kind.DOWNTIME -> R.string.detail_comment_downtime
    Comment.Kind.FLAPPING -> R.string.detail_comment_flapping
    Comment.Kind.OTHER -> R.string.detail_comment_other
}

/** How many comments are shown before "Show all". Also how many the cache keeps. */
private const val COMMENTS_SHOWN = 20
