// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.CheckStatus

@Composable
internal fun checkTimes(check: CheckStatus): String {
    val parts = buildList {
        check.lastCheck?.let { add(stringResource(R.string.detail_last_check, formatWhen(it))) }
        check.nextCheck?.let { add(stringResource(R.string.detail_next_check, formatWhen(it))) }
        add(stringResource(if (check.activeCheck) R.string.detail_active else R.string.detail_passive))
    }
    return parts.joinToString(" · ")
}

/** What the plugin said. Selectable, because the next step is often to paste it somewhere. */
@Composable
internal fun Output(check: CheckStatus) {
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
internal fun Flags(check: CheckStatus) {
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
internal fun Fact(label: Int, value: String) {
    Text(
        stringResource(R.string.detail_fact, stringResource(label), value),
        style = MaterialTheme.typography.bodyMedium,
    )
}
