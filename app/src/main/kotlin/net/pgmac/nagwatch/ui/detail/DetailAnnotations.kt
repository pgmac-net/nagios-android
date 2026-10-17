// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.status.AnnotationSection
import net.pgmac.nagwatch.ui.home.message

/** Newest first, each its own row: a busy object can have a couple of hundred. */
internal fun LazyListScope.comments(section: AnnotationSection<Comment>, all: Boolean, onToggleAll: () -> Unit) {
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

internal fun LazyListScope.downtimes(
    section: AnnotationSection<Downtime>,
    canCancel: Boolean,
    onCancel: (Long) -> Unit,
) {
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
        TextButton(
            onClick = { onCancel(downtime.id) },
            enabled = canCancel,
            modifier = Modifier.padding(horizontal = 4.dp).testTag(DetailTags.cancelDowntime(downtime.id)),
        ) {
            Text(stringResource(R.string.action_cancel_downtime))
        }
    }
}

/**
 * The heading of the comments or the downtimes, and whichever of their states
 * applies. When they could not be fetched the reason is given here and only
 * here: the rest of the screen is unaffected, and anything saved from an
 * earlier visit still shows beneath.
 */
@Composable
internal fun AnnotationHeader(title: Int, unavailable: Int, none: Int, tag: String, section: AnnotationSection<*>) {
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
internal fun Annotation(heading: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun Comment.Kind.label(): Int = when (this) {
    Comment.Kind.USER -> R.string.detail_comment_user
    Comment.Kind.ACKNOWLEDGEMENT -> R.string.detail_comment_acknowledgement
    Comment.Kind.DOWNTIME -> R.string.detail_comment_downtime
    Comment.Kind.FLAPPING -> R.string.detail_comment_flapping
    Comment.Kind.OTHER -> R.string.detail_comment_other
}

/** How many comments are shown before "Show all". Also how many the cache keeps. */
internal const val COMMENTS_SHOWN = 20
