// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.Marker
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.problems.formatAge
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.toUiText

// The pieces every tab shows the same way: a state badge, when the data is from, and the
// banners that say it may not be current or complete.

/** A state in a coloured box: "CRIT", "DOWN", "OK". The colour never carries the meaning alone. */
@Composable
internal fun StateBadge(@StringRes label: Int, color: Color) {
    Text(
        text = stringResource(label),
        color = NagwatchTheme.stateColors.onState,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.background(
            color,
            RoundedCornerShape(BADGE_RADIUS),
        ).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun UpdatedLabel(fetchedAt: Instant, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.problems_updated, clockTime(fetchedAt)),
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.testTag(HomeTags.UPDATED),
    )
}

/** Each one says plainly that what is shown may not be current, or may be incomplete. */
@Composable
internal fun StatusBanners(status: ProfileStatus, now: Instant) {
    val error = status.error
    if (error != null) {
        Banner(stringResource(R.string.problems_banner_failed, error.message()), HomeTags.BANNER_FAILED)
    }
    if (status.isStale(now)) {
        Banner(
            stringResource(R.string.problems_banner_stale, formatAge(status.lastSuccess, now)),
            HomeTags.BANNER_STALE,
        )
    }
    val degraded = status.report?.degradedCount ?: 0
    if (degraded > 0) {
        Banner(pluralStringResource(R.plurals.problems_banner_degraded, degraded, degraded), HomeTags.BANNER_DEGRADED)
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

/**
 * Why a row looks the way it does: soft attempt count, silenced checks, handled-by.
 * [check] is null when only the state is known, and then there is nothing to say.
 */
@Composable
internal fun checkNotes(markers: Set<Marker>, handling: Handling?, check: CheckStatus?): String {
    val parts = buildList {
        if (Marker.SOFT in markers && check != null) {
            add(stringResource(R.string.note_soft, check.currentAttempt, check.maxAttempts))
        }
        if (Marker.CHECKS_DISABLED in markers) add(stringResource(R.string.note_checks_disabled))
        if (Marker.NOTIFICATIONS_DISABLED in markers) add(stringResource(R.string.note_notifications_disabled))
        if (Marker.DETAILS_UNAVAILABLE in markers) add(stringResource(R.string.note_details_unavailable))
        handling?.reasonText()?.let(::add)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Handling.reasonText(): String? = when (this) {
    Handling.ACKNOWLEDGED -> stringResource(R.string.note_acknowledged)
    Handling.IN_DOWNTIME -> stringResource(R.string.note_downtime)
    Handling.HOST_DOWN -> stringResource(R.string.note_host_down)
    Handling.UNHANDLED -> null
}

@Composable
internal fun StatusError?.message(): String = when (this) {
    null -> ""
    is StatusError.Nagios -> error.toUiText().resolve()
    StatusError.CredentialsUnavailable -> stringResource(R.string.profile_credentials_unavailable)
    StatusError.InvalidUrl -> stringResource(R.string.problem_url_invalid)
    StatusError.ProfileMissing -> stringResource(R.string.problems_profile_missing)
}

private fun clockTime(instant: Instant): String = TIME.format(instant.atZone(ZoneId.systemDefault()))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val BADGE_RADIUS = 4
