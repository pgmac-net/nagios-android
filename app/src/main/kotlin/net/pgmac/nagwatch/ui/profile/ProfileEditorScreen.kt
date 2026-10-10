// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.ui.toUiText

object ProfileEditorTags {
    const val URL = "editor_url"
    const val CLEARTEXT_WARNING = "editor_cleartext_warning"
    const val CLEARTEXT_SWITCH = "editor_cleartext_switch"
    const val PASSWORD = "editor_password"
    const val PASSWORD_SAVED = "editor_password_saved"
    const val TEST_RESULT = "editor_test_result"
    const val TEST_NOTE = "editor_test_note"
    const val PROBLEMS = "editor_problems"
    const val SAVE = "editor_save"
    const val TEST = "editor_test"
}

/** What the editor can ask for. One object rather than a dozen lambdas. */
interface ProfileEditorActions {
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

    val actions = object : ProfileEditorActions {
        override fun edit(change: (ProfileEditorState) -> ProfileEditorState) = viewModel.edit(change)

        override fun testConnection() = viewModel.testConnection()

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
                Problems(state.problems)
                TestResult(state.test)
                Buttons(state, actions)
            }
        }
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

@Composable
private fun AccessFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    Text(stringResource(R.string.profile_access_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.profile_access_hint), style = MaterialTheme.typography.bodySmall)
    val isError = state.problems.any { it in ACCESS_PROBLEMS }
    Field(R.string.profile_access_client_id, state.accessClientId, isError = isError) { value ->
        actions.edit { it.copy(accessClientId = value) }
    }
    SecretInputField(R.string.profile_access_client_secret, state.accessClientSecret, isError = isError) { field ->
        actions.edit { it.copy(accessClientSecret = field) }
    }
}

@Composable
private fun HeaderFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    Text(stringResource(R.string.profile_headers_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.profile_headers_hint), style = MaterialTheme.typography.bodySmall)
    val isError = state.problems.any { it in HEADER_PROBLEMS }
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
private fun TestResult(test: TestState) {
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
}
