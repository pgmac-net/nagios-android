// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.toUiText

object ProblemsTags {
    const val NO_PROFILES = "problems_no_profiles"
    const val ADD_PROFILE = "problems_add_profile"
    const val LOADING = "problems_loading"
    const val ERROR = "problems_error"
    const val BANNER_FAILED = "problems_banner_failed"
    const val BANNER_STALE = "problems_banner_stale"
    const val BANNER_DEGRADED = "problems_banner_degraded"
    const val ALL_CLEAR = "problems_all_clear"
    const val LIST = "problems_list"
    const val HANDLED_TOGGLE = "problems_handled_toggle"
    const val UPDATED = "problems_updated"
    const val PROFILE_MENU = "problems_profile_menu"

    fun chip(kind: ProblemKind) = "problems_chip_${kind.name}"
}

/** What the screen can ask for. */
interface ProblemsActions {
    fun refresh()

    fun toggleFilter(kind: ProblemKind)

    fun selectProfile(id: Long)

    fun addProfile()

    fun editProfile(id: Long)

    fun manageProfiles()
}

@Composable
fun ProblemsScreen(
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
    onManageProfiles: () -> Unit,
    viewModel: ProblemsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Opening the screen and coming back to it both refresh, but only if what is held is old.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshIfNeeded() }

    val actions = object : ProblemsActions {
        override fun refresh() = viewModel.refresh()

        override fun toggleFilter(kind: ProblemKind) = viewModel.toggleFilter(kind)

        override fun selectProfile(id: Long) = viewModel.select(id)

        override fun addProfile() = onAddProfile()

        override fun editProfile(id: Long) = onEditProfile(id)

        override fun manageProfiles() = onManageProfiles()
    }
    ProblemsContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemsContent(state: ProblemsUiState, actions: ProblemsActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { ProfileSwitcher(state, actions) }) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val selected = state.selected
            when {
                state.loading -> Text(
                    stringResource(R.string.problems_loading),
                    modifier = Modifier.padding(24.dp).testTag(ProblemsTags.LOADING),
                )

                selected == null -> NoProfiles(actions)

                else -> ProfileProblems(state, selected, actions)
            }
        }
    }
}

@Composable
private fun ProfileSwitcher(state: ProblemsUiState, actions: ProblemsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(ProblemsTags.PROFILE_MENU)) {
            Text(
                text = state.selected?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        open = false
                        actions.selectProfile(profile.id)
                    },
                )
            }
            HorizontalDivider()
            state.selected?.let { selected ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.problems_edit_profile)) },
                    onClick = {
                        open = false
                        actions.editProfile(selected.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.problems_manage_profiles)) },
                onClick = {
                    open = false
                    actions.manageProfiles()
                },
            )
        }
    }
}

/** First run: nothing to show until a profile exists, so say what to do. */
@Composable
private fun NoProfiles(actions: ProblemsActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(ProblemsTags.NO_PROFILES),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.profiles_empty_text))
        Button(onClick = actions::addProfile, modifier = Modifier.testTag(ProblemsTags.ADD_PROFILE)) {
            Text(stringResource(R.string.profiles_add))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileProblems(state: ProblemsUiState, profile: Profile, actions: ProblemsActions) {
    val status = state.status
    val refreshing = status?.refreshing == true
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
        when {
            // Nothing fetched yet and nothing to show: a spinner is the pull indicator, add words.
            status?.report == null && status?.error == null -> Text(
                stringResource(R.string.problems_loading),
                modifier = Modifier.padding(24.dp).testTag(ProblemsTags.LOADING),
            )

            status.report == null -> FirstFailure(status.error, profile, actions)

            else -> ProblemList(state, status, actions)
        }
    }
}

/** The first fetch failed, so there is no earlier result to fall back on. */
@Composable
private fun FirstFailure(error: StatusError?, profile: Profile, actions: ProblemsActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(ProblemsTags.ERROR),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.problems_failed_title), style = MaterialTheme.typography.titleLarge)
        Text(error.message())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions::refresh) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = { actions.editProfile(profile.id) }) {
                Text(stringResource(R.string.problems_edit_profile))
            }
        }
    }
}

@Composable
private fun ProblemList(state: ProblemsUiState, status: ProfileStatus, actions: ProblemsActions) {
    val report = checkNotNull(status.report)
    var handledOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(ProblemsTags.LIST)) {
        item { SummaryHeader(report.counts, report.fetchedAt, state, actions) }
        item { Banners(status, report.degradedCount, state.now) }

        val unhandled = state.visibleUnhandled
        if (unhandled.isEmpty()) {
            // "Everything is OK" is only true if nothing is handled either: an acknowledged
            // critical is still a critical.
            val nothingAtAll = report.unhandled.isEmpty() && report.handled.isEmpty()
            item { AllClear(nothingAtAll, filtered = state.filter.isNotEmpty()) }
        } else {
            items(unhandled, key = ::rowKey) { problem ->
                ProblemRow(problem, state.now)
                HorizontalDivider()
            }
        }

        val handled = state.visibleHandled
        if (handled.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { handledOpen = !handledOpen },
                    modifier = Modifier.testTag(ProblemsTags.HANDLED_TOGGLE),
                ) {
                    val label = if (handledOpen) R.string.problems_handled_hide else R.string.problems_handled_show
                    Text(stringResource(label, handled.size))
                }
            }
            if (handledOpen) {
                items(handled, key = { "handled-${rowKey(it)}" }) { problem ->
                    ProblemRow(problem, state.now)
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryHeader(
    counts: ProblemCounts,
    fetchedAt: Instant,
    state: ProblemsUiState,
    actions: ProblemsActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Four chips do not fit one row on a phone. In a plain Row the last one was squeezed to
        // nothing and its label wrapped a letter per line, stretching the header down the screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountChip(
                ProblemKind.HOSTS,
                pluralStringResource(R.plurals.chip_hosts, counts.hostsDown, counts.hostsDown),
                state,
                actions,
            )
            CountChip(ProblemKind.CRITICAL, stringResource(R.string.chip_critical, counts.critical), state, actions)
            CountChip(ProblemKind.WARNING, stringResource(R.string.chip_warning, counts.warning), state, actions)
            CountChip(ProblemKind.UNKNOWN, stringResource(R.string.chip_unknown, counts.unknown), state, actions)
        }
        Text(
            text = stringResource(R.string.problems_updated, clockTime(fetchedAt)),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(ProblemsTags.UPDATED),
        )
    }
}

@Composable
private fun CountChip(kind: ProblemKind, label: String, state: ProblemsUiState, actions: ProblemsActions) {
    FilterChip(
        selected = kind in state.filter,
        onClick = { actions.toggleFilter(kind) },
        label = { Text(label, maxLines = 1, softWrap = false) },
        modifier = Modifier.testTag(ProblemsTags.chip(kind)),
    )
}

/** Each one says plainly that what is shown may not be current, or may be incomplete. */
@Composable
private fun Banners(status: ProfileStatus, degradedCount: Int, now: Instant) {
    val error = status.error
    if (error != null) {
        Banner(
            text = stringResource(R.string.problems_banner_failed, error.message()),
            tag = ProblemsTags.BANNER_FAILED,
        )
    }
    if (status.isStale(now)) {
        Banner(
            text = stringResource(R.string.problems_banner_stale, formatAge(status.lastSuccess, now)),
            tag = ProblemsTags.BANNER_STALE,
        )
    }
    if (degradedCount > 0) {
        Banner(
            text = pluralStringResource(R.plurals.problems_banner_degraded, degradedCount, degradedCount),
            tag = ProblemsTags.BANNER_DEGRADED,
        )
    }
}

@Composable
private fun Banner(text: String, tag: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
    )
}

@Composable
private fun AllClear(nothingAtAll: Boolean, filtered: Boolean) {
    val text = when {
        filtered -> R.string.problems_none_filtered
        nothingAtAll -> R.string.problems_all_clear
        else -> R.string.problems_all_handled
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = NagwatchTheme.stateColors.ok,
        modifier = Modifier.padding(24.dp).testTag(ProblemsTags.ALL_CLEAR),
    )
}

@Composable
private fun ProblemRow(problem: Problem, now: Instant) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeverityBadge(problem.severity)
            Text(
                text = problem.title(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatAge(problem.since, now), style = MaterialTheme.typography.labelMedium)
        }
        val output = problem.output()
        if (output.isNotEmpty()) {
            Text(output, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val notes = problem.notes()
        if (notes.isNotEmpty()) {
            Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (problem is HostProblem) problem.services.forEach { RolledUpService(it) }
    }
}

/** A service on a down host: shown under the host, not counted on its own. */
@Composable
private fun RolledUpService(problem: ServiceProblem) {
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(problem.severity)
        Text(
            text = problem.service.description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeverityBadge(severity: Severity) {
    val colors = NagwatchTheme.stateColors
    val (label, color) = when (severity) {
        Severity.HOST_DOWN -> R.string.severity_down to colors.critical
        Severity.HOST_UNREACHABLE -> R.string.severity_unreachable to colors.unknown
        Severity.CRITICAL -> R.string.severity_critical to colors.critical
        Severity.WARNING -> R.string.severity_warning to colors.warning
        Severity.UNKNOWN -> R.string.severity_unknown to colors.unknown
    }
    Text(
        text = stringResource(label),
        color = colors.onState,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(
            color,
            RoundedCornerShape(BADGE_RADIUS),
        ).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Why a problem looks the way it does: soft attempt count, silenced checks, handled-by. */
@Composable
private fun Problem.notes(): String {
    val parts = buildList {
        if (Marker.SOFT in markers) add(stringResource(R.string.note_soft, check.currentAttempt, check.maxAttempts))
        if (Marker.CHECKS_DISABLED in markers) add(stringResource(R.string.note_checks_disabled))
        if (Marker.NOTIFICATIONS_DISABLED in markers) add(stringResource(R.string.note_notifications_disabled))
        if (Marker.DETAILS_UNAVAILABLE in markers) add(stringResource(R.string.note_details_unavailable))
        handling.reasonText()?.let(::add)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Handling.reasonText(): String? = when (this) {
    Handling.ACKNOWLEDGED -> stringResource(R.string.note_acknowledged)
    Handling.IN_DOWNTIME -> stringResource(R.string.note_downtime)
    Handling.HOST_DOWN, Handling.UNHANDLED -> null
}

@Composable
private fun StatusError?.message(): String = when (this) {
    null -> ""
    is StatusError.Nagios -> error.toUiText().resolve()
    StatusError.CredentialsUnavailable -> stringResource(R.string.profile_credentials_unavailable)
    StatusError.InvalidUrl -> stringResource(R.string.problem_url_invalid)
    StatusError.ProfileMissing -> stringResource(R.string.problems_profile_missing)
}

/** Stable per row, so scrolling and expanding keep their place across refreshes. */
private fun rowKey(problem: Problem): String = when (problem) {
    is HostProblem -> "host:${problem.host.name}"
    is ServiceProblem -> "service:${problem.service.hostName}/${problem.service.description}"
}

private fun clockTime(instant: Instant): String = TIME.format(instant.atZone(ZoneId.systemDefault()))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val BADGE_RADIUS = 4
