// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.ui.NagwatchTopAppBar
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
    const val ACK_COMMENT = "editor_ack_comment"
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
            NagwatchTopAppBar(
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
                QuickActionFields(state, actions)
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
