// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.ui.localnet.AllowLocalNetworkButton
import net.pgmac.nagwatch.ui.localnet.LocalNetworkActions
import net.pgmac.nagwatch.ui.localnet.LocalNetworkExplanation
import net.pgmac.nagwatch.ui.localnet.rememberLocalNetworkRequest
import net.pgmac.nagwatch.ui.notify.BackgroundCheckFields
import net.pgmac.nagwatch.ui.notify.NotificationFieldActions
import net.pgmac.nagwatch.ui.notify.NotificationFields
import net.pgmac.nagwatch.ui.notify.rememberNotificationRequest
import net.pgmac.nagwatch.ui.toUiText

object ProfileEditorTags {
    const val URL = "editor_url"
    const val CLEARTEXT_WARNING = "editor_cleartext_warning"
    const val CLEARTEXT_SWITCH = "editor_cleartext_switch"
    const val PASSWORD = "editor_password"
    const val PASSWORD_SAVED = "editor_password_saved"
    const val TEST_RESULT = "editor_test_result"
    const val TEST_NOTE = "editor_test_note"
    const val TEST_READ_ONLY = "editor_test_read_only"
    const val PROBLEMS = "editor_problems"
    const val GRAPHS_SWITCH = "editor_graphs_switch"
    const val GRAPH_PATH = "editor_graph_path"
    const val ACCESS_TOGGLE = "editor_access_toggle"
    const val ACCESS_SET = "editor_access_set"
    const val ACCESS_FIELDS = "editor_access_fields"
    const val HEADERS_TOGGLE = "editor_headers_toggle"
    const val HEADERS_SET = "editor_headers_set"
    const val HEADERS_FIELDS = "editor_headers_fields"
    const val SAVE = "editor_save"
    const val TEST = "editor_test"
}

/** What the editor can ask for. One object rather than a dozen lambdas. */
interface ProfileEditorActions :
    LocalNetworkActions,
    NotificationFieldActions {
    fun edit(change: (ProfileEditorState) -> ProfileEditorState)

    fun testConnection()

    fun save()

    fun delete()

    fun close()
}

@Composable
fun ProfileEditorScreen(onDone: () -> Unit, viewModel: ProfileEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    val request = rememberLocalNetworkRequest(viewModel::localNetworkAnswered)
    val notificationRequest = rememberNotificationRequest(viewModel::notificationsAnswered)
    val actions = object : ProfileEditorActions {
        override fun edit(change: (ProfileEditorState) -> ProfileEditorState) = viewModel.edit(change)

        override fun setNotifications(enabled: Boolean) = viewModel.setNotifications(enabled, notificationRequest)

        override fun editNotifications(change: (NotificationSettings) -> NotificationSettings) =
            viewModel.edit { it.copy(notifications = change(it.notifications)) }

        override fun testConnection() = viewModel.testConnection()

        override fun localNetworkContinue() {
            viewModel.localNetworkExplained()
            request.askAndroid()
        }

        override fun localNetworkNotNow() = viewModel.localNetworkDeclined()

        override fun allowLocalNetwork() = viewModel.allowLocalNetwork(request)

        override fun save() = viewModel.save()

        override fun delete() = viewModel.delete()

        override fun close() = onDone()
    }
    ProfileEditorContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorContent(state: ProfileEditorState, actions: ProfileEditorActions, modifier: Modifier = Modifier) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.profile_new else R.string.profile_edit)) },
                navigationIcon = {
                    TextButton(onClick = actions::close) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    if (!state.isNew) {
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(innerPadding).padding(24.dp))
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionFields(state, actions)
                HorizontalDivider()
                AccessFields(state, actions)
                HorizontalDivider()
                HeaderFields(state, actions)
                HorizontalDivider()
                BackgroundCheckFields(state.notifications.interval) { interval ->
                    actions.editNotifications { it.copy(interval = interval) }
                }
                HorizontalDivider()
                NotificationFields(state.notifications, state.notificationsRefused, actions)
                HorizontalDivider()
                GraphFields(state, actions)
                HorizontalDivider()
                Problems(state.problems)
                TestResult(state.test, actions)
                Buttons(state, actions)
            }
        }
    }
    if (state.explainLocalNetwork) {
        LocalNetworkExplanation(onContinue = actions::localNetworkContinue, onNotNow = actions::localNetworkNotNow)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_text, state.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.delete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectionFields(state: ProfileEditorState, actions: ProfileEditorActions) {
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
private fun FoldingSection(
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

private class FoldTags(val toggle: String, val set: String, val fields: String)

@Composable
private fun AccessFields(state: ProfileEditorState, actions: ProfileEditorActions) {
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
private fun HeaderFields(state: ProfileEditorState, actions: ProfileEditorActions) {
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
private fun HeaderRows(state: ProfileEditorState, actions: ProfileEditorActions, isError: Boolean) {
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
 * The "Graphs" section. The path is shown only while graphs are on: with them off it decides
 * nothing. It is still checked on save, so that what is stored can be used when they come back.
 */
@Composable
private fun GraphFields(state: ProfileEditorState, actions: ProfileEditorActions) {
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

@Composable
private fun Problems(problems: Set<ProfileProblem>) {
    if (problems.isEmpty()) return
    Column(modifier = Modifier.testTag(ProfileEditorTags.PROBLEMS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        problems.sorted().forEach { problem ->
            Text(
                text = stringResource(problem.message()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TestResult(test: TestState, actions: ProfileEditorActions) {
    val (text, isError) = when (test) {
        TestState.Idle -> return
        TestState.Running -> stringResource(R.string.profile_test_running) to false
        is TestState.Succeeded -> stringResource(R.string.profile_test_success, test.nagiosVersion) to false
        is TestState.Failed -> test.error.toUiText().resolve() to true
        TestState.CredentialsUnavailable -> stringResource(R.string.profile_credentials_unavailable) to true
    }
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(ProfileEditorTags.TEST_RESULT),
    )
    // It connected, so nothing is wrong; this only says what to expect from an older server.
    if (test is TestState.Succeeded && NagiosVersion.isOlderThanTested(test.nagiosVersion)) {
        Text(
            text = stringResource(R.string.profile_test_old_version),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(ProfileEditorTags.TEST_NOTE),
        )
    }
    // Probably Android, not the network: the way to put it right goes with the news.
    if (test is TestState.Failed && test.error == NagiosError.LocalNetworkBlocked) {
        AllowLocalNetworkButton(onClick = actions::allowLocalNetwork)
    }
    // Not a failure either: plenty of people give a phone a user that can only look.
    if (test is TestState.Succeeded && test.readOnly) {
        Text(
            text = stringResource(R.string.profile_test_read_only),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(ProfileEditorTags.TEST_READ_ONLY),
        )
    }
}

@Composable
private fun Buttons(state: ProfileEditorState, actions: ProfileEditorActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = actions::testConnection,
            enabled = state.test != TestState.Running,
            modifier = Modifier.testTag(ProfileEditorTags.TEST),
        ) { Text(stringResource(R.string.profile_test)) }
        Button(onClick = actions::save, modifier = Modifier.testTag(ProfileEditorTags.SAVE)) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        isError = isError,
        singleLine = true,
        visualTransformation = visualTransformation,
        // Credentials and URLs must not be learned by the keyboard.
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A stored secret is shown as "saved" with a Replace button and is never put
 * back into a text field. While typing, the value is masked.
 */
@Composable
private fun SecretInputField(
    label: Int,
    field: SecretField,
    isError: Boolean = false,
    fieldTag: String? = null,
    savedTag: String? = null,
    onChange: (SecretField) -> Unit,
) {
    if (field.editing) {
        Field(
            label = label,
            value = field.input,
            isError = isError,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            modifier = if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier,
        ) { onChange(field.copy(input = it)) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.profile_secret_saved, stringResource(label)),
                modifier = if (savedTag != null) Modifier.testTag(savedTag) else Modifier,
            )
            TextButton(onClick = { onChange(field.copy(editing = true, input = "")) }) {
                Text(stringResource(R.string.action_replace))
            }
        }
    }
}

private fun <T> List<T>.replaced(index: Int, change: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) change(item) else item }

private val URL_PROBLEMS = setOf(
    ProfileProblem.URL_REQUIRED,
    ProfileProblem.URL_INVALID,
    ProfileProblem.CLEARTEXT_NOT_ALLOWED,
    ProfileProblem.ACCESS_OVER_CLEARTEXT,
)
private val ACCESS_PROBLEMS = setOf(ProfileProblem.ACCESS_OVER_CLEARTEXT, ProfileProblem.ACCESS_INCOMPLETE)
private val HEADER_PROBLEMS = setOf(
    ProfileProblem.HEADER_NAME_INVALID,
    ProfileProblem.HEADER_NAME_RESERVED,
    ProfileProblem.HEADER_VALUE_REQUIRED,
)

private fun ProfileProblem.message(): Int = when (this) {
    ProfileProblem.NAME_REQUIRED -> R.string.problem_name_required
    ProfileProblem.URL_REQUIRED -> R.string.problem_url_required
    ProfileProblem.URL_INVALID -> R.string.problem_url_invalid
    ProfileProblem.USERNAME_REQUIRED -> R.string.problem_username_required
    ProfileProblem.PASSWORD_REQUIRED -> R.string.problem_password_required
    ProfileProblem.CLEARTEXT_NOT_ALLOWED -> R.string.problem_cleartext_not_allowed
    ProfileProblem.ACCESS_OVER_CLEARTEXT -> R.string.problem_access_over_cleartext
    ProfileProblem.ACCESS_INCOMPLETE -> R.string.problem_access_incomplete
    ProfileProblem.HEADER_NAME_INVALID -> R.string.problem_header_name_invalid
    ProfileProblem.HEADER_NAME_RESERVED -> R.string.problem_header_name_reserved
    ProfileProblem.HEADER_VALUE_REQUIRED -> R.string.problem_header_value_required
    ProfileProblem.GRAPH_PATH_INVALID -> R.string.problem_graph_path_invalid
}
