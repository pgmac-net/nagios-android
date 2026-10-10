// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.problems

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.ui.home.HomeActions
import net.pgmac.nagwatch.ui.home.HomeUiState
import net.pgmac.nagwatch.ui.home.StateBadge
import net.pgmac.nagwatch.ui.home.StateChip
import net.pgmac.nagwatch.ui.home.StatusBanners
import net.pgmac.nagwatch.ui.home.UpdatedLabel
import net.pgmac.nagwatch.ui.home.checkNotes
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

/** The Problems tab: what needs attention now, worst first, with the handled ones folded away. */
@Composable
internal fun ProblemsTab(state: HomeUiState, status: ProfileStatus, actions: HomeActions) {
    val report = checkNotNull(status.report)
    var handledOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(ProblemsTags.LIST)) {
        item { SummaryHeader(report.counts, report.fetchedAt, state, actions) }
        item { StatusBanners(status, state.now) }

        val unhandled = state.visibleUnhandled
        if (unhandled.isEmpty()) {
            // "Everything is OK" is only true if nothing is handled either: an acknowledged
            // critical is still a critical.
            val nothingAtAll = report.unhandled.isEmpty() && report.handled.isEmpty()
            item { AllClear(nothingAtAll, filtered = state.filter.isNotEmpty()) }
        } else {
            items(unhandled, key = ::rowKey) { problem ->
                ProblemRow(problem, state.now, actions)
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
                    ProblemRow(problem, state.now, actions)
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryHeader(counts: ProblemCounts, fetchedAt: Instant, state: HomeUiState, actions: HomeActions) {
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
        UpdatedLabel(fetchedAt)
    }
}

@Composable
private fun CountChip(kind: ProblemKind, label: String, state: HomeUiState, actions: HomeActions) {
    val colors = NagwatchTheme.stateColors
    val color = when (kind) {
        ProblemKind.HOSTS, ProblemKind.CRITICAL -> colors.critical
        ProblemKind.WARNING -> colors.warning
        ProblemKind.UNKNOWN -> colors.unknown
    }
    StateChip(
        label = label,
        selected = kind in state.filter,
        color = color,
        tag = ProblemsTags.chip(kind),
        onClick = { actions.toggleFilter(kind) },
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
private fun ProblemRow(problem: Problem, now: Instant, actions: HomeActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { problem.open(actions) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        val notes = checkNotes(problem.markers, problem.handling, problem.check)
        if (notes.isNotEmpty()) {
            Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (problem is HostProblem) problem.services.forEach { RolledUpService(it) }
    }
}

private fun Problem.open(actions: HomeActions) = when (this) {
    is HostProblem -> actions.openHost(host.name)
    is ServiceProblem -> actions.openService(service.hostName, service.description)
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
    StateBadge(label, color)
}

/** Stable per row, so scrolling and expanding keep their place across refreshes. */
private fun rowKey(problem: Problem): String = when (problem) {
    is HostProblem -> "host:${problem.host.name}"
    is ServiceProblem -> "service:${problem.service.hostName}/${problem.service.description}"
}
