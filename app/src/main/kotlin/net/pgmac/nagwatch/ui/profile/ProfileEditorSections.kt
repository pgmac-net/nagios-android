// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.ProfileProblem

@Composable
internal fun ConnectionFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    Field(R.string.profile_name, state.name, isError = ProfileProblem.NAME_REQUIRED in state.problems) { value ->
        actions.edit { it.copy(name = value) }
    }
    Field(
        label = R.string.profile_url,
        value = state.baseUrl,
        isError = state.problems.any { it in URL_PROBLEMS },
        supporting = R.string.profile_url_hint,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.testTag(ProfileEditorTags.URL),
    ) { value -> actions.edit { it.copy(baseUrl = value) } }

    if (state.isCleartextUrl) {
        Text(
            text = stringResource(R.string.profile_cleartext_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(ProfileEditorTags.CLEARTEXT_WARNING),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = state.allowCleartext,
                onCheckedChange = { checked -> actions.edit { it.copy(allowCleartext = checked) } },
                modifier = Modifier.testTag(ProfileEditorTags.CLEARTEXT_SWITCH),
            )
            Text(stringResource(R.string.profile_allow_cleartext))
        }
    }

    Field(R.string.profile_username, state.username, isError = ProfileProblem.USERNAME_REQUIRED in state.problems) {
        actions.edit { state -> state.copy(username = it) }
    }
    SecretInputField(
        label = R.string.profile_password,
        field = state.password,
        isError = ProfileProblem.PASSWORD_REQUIRED in state.problems,
        fieldTag = ProfileEditorTags.PASSWORD,
        savedTag = ProfileEditorTags.PASSWORD_SAVED,
    ) { field -> actions.edit { it.copy(password = field) } }
}

/**
 * An optional block of the form that can be folded away. Folding only hides: what is in it is
 * still saved and still sent, so [hasValue] puts "Set" on the title row while it is folded and
 * a fold can never make something that is in use look absent. It starts open when there is
 * something in it, and opens by itself when it has a problem, so an error is never out of sight.
 */
@Composable
internal fun FoldingSection(
    @StringRes title: Int,
    hasValue: Boolean,
    hasProblem: Boolean,
    tags: FoldTags,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(hasValue || hasProblem) }
    LaunchedEffect(hasProblem) { if (hasProblem) open = true }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.testTag(tags.toggle),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (hasValue && !open) {
            Text(
                text = stringResource(R.string.profile_section_set),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp).testTag(tags.set),
            )
        }
        Text(
            text = stringResource(if (open) R.string.profile_section_hide else R.string.profile_section_show),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag(tags.fields),
        ) { content() }
    }
}

internal class FoldTags(val toggle: String, val set: String, val fields: String)

@Composable
internal fun AccessFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    val isError = state.problems.any { it in ACCESS_PROBLEMS }
    FoldingSection(
        title = R.string.profile_access_title,
        hasValue = state.accessClientId.isNotBlank() || state.accessClientSecret.stored ||
            state.accessClientSecret.input.isNotEmpty(),
        hasProblem = isError,
        tags = FoldTags(
            ProfileEditorTags.ACCESS_TOGGLE,
            ProfileEditorTags.ACCESS_SET,
            ProfileEditorTags.ACCESS_FIELDS,
        ),
    ) {
        Text(stringResource(R.string.profile_access_hint), style = MaterialTheme.typography.bodySmall)
        Field(R.string.profile_access_client_id, state.accessClientId, isError = isError) { value ->
            actions.edit { it.copy(accessClientId = value) }
        }
        SecretInputField(R.string.profile_access_client_secret, state.accessClientSecret, isError = isError) { field ->
            actions.edit { it.copy(accessClientSecret = field) }
        }
    }
}

@Composable
internal fun HeaderFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    val isError = state.problems.any { it in HEADER_PROBLEMS }
    FoldingSection(
        title = R.string.profile_headers_title,
        hasValue = state.headers.isNotEmpty(),
        hasProblem = isError,
        tags = FoldTags(
            ProfileEditorTags.HEADERS_TOGGLE,
            ProfileEditorTags.HEADERS_SET,
            ProfileEditorTags.HEADERS_FIELDS,
        ),
    ) {
        HeaderRows(state, actions, isError)
    }
}

@Composable
internal fun HeaderRows(state: ProfileEditorState, actions: ProfileEditorActions, isError: Boolean) {
    Text(stringResource(R.string.profile_headers_hint), style = MaterialTheme.typography.bodySmall)
    state.headers.forEachIndexed { index, header ->
        Field(R.string.profile_header_name, header.name, isError = isError) { value ->
            actions.edit { it.copy(headers = it.headers.replaced(index) { h -> h.copy(name = value) }) }
        }
        SecretInputField(R.string.profile_header_value, header.value, isError = isError) { field ->
            actions.edit { it.copy(headers = it.headers.replaced(index) { h -> h.copy(value = field) }) }
        }
        TextButton(onClick = { actions.edit { it.copy(headers = it.headers.filterIndexed { i, _ -> i != index }) } }) {
            Text(stringResource(R.string.profile_header_remove))
        }
    }
    OutlinedButton(onClick = { actions.edit { it.copy(headers = it.headers + HeaderField()) } }) {
        Text(stringResource(R.string.profile_header_add))
    }
}

/**
 * The "Quick actions" section: what an acknowledgement made without the form says. It is asked
 * for here, once, because a quick action does not stop to ask (docs/adr/0012).
 */
@Composable
internal fun QuickActionFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.titleMedium)
    Field(
        label = R.string.quick_ack_comment,
        value = state.ackComment,
        isError = false,
        supporting = R.string.quick_ack_comment_hint,
        modifier = Modifier.testTag(ProfileEditorTags.ACK_COMMENT),
    ) { value -> actions.edit { it.copy(ackComment = value) } }
}

/**
 * The "Graphs" section. The path is shown only while graphs are on: with them off it decides
 * nothing. It is still checked on save, so that what is stored can be used when they come back.
 */
@Composable
internal fun GraphFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    Text(stringResource(R.string.graphs_title), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = state.graphs.enabled,
            onCheckedChange = { checked -> actions.edit { it.copy(graphs = it.graphs.copy(enabled = checked)) } },
            modifier = Modifier.testTag(ProfileEditorTags.GRAPHS_SWITCH),
        )
        Text(stringResource(R.string.graphs_switch))
    }
    Text(stringResource(R.string.graphs_hint), style = MaterialTheme.typography.bodySmall)
    if (state.graphs.enabled) {
        Field(
            label = R.string.graphs_path,
            value = state.graphs.path,
            isError = ProfileProblem.GRAPH_PATH_INVALID in state.problems,
            supporting = R.string.graphs_path_hint,
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.testTag(ProfileEditorTags.GRAPH_PATH),
        ) { value -> actions.edit { it.copy(graphs = it.graphs.copy(path = value)) } }
    }
}

internal fun <T> List<T>.replaced(index: Int, change: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) change(item) else item }

internal val URL_PROBLEMS = setOf(
    ProfileProblem.URL_REQUIRED,
    ProfileProblem.URL_INVALID,
    ProfileProblem.CLEARTEXT_NOT_ALLOWED,
    ProfileProblem.ACCESS_OVER_CLEARTEXT,
)
internal val ACCESS_PROBLEMS = setOf(ProfileProblem.ACCESS_OVER_CLEARTEXT, ProfileProblem.ACCESS_INCOMPLETE)
internal val HEADER_PROBLEMS = setOf(
    ProfileProblem.HEADER_NAME_INVALID,
    ProfileProblem.HEADER_NAME_RESERVED,
    ProfileProblem.HEADER_VALUE_REQUIRED,
)
