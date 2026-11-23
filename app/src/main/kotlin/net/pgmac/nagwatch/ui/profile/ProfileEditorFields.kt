// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import net.pgmac.nagwatch.R

@Composable
internal fun Field(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        isError = isError,
        singleLine = true,
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
internal fun SecretInputField(
    label: Int,
    field: SecretField,
    isError: Boolean = false,
    fieldTag: String? = null,
    savedTag: String? = null,
    onChange: (SecretField) -> Unit,
) {
    if (field.editing) {
        MaskedField(
            label = label,
            value = field.input,
            isError = isError,
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

/** A masked single-line input. [value] seeds the field; every edit is reported through [onChange]. */
@Composable
private fun MaskedField(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onChange: (String) -> Unit,
) {
    val state = rememberTextFieldState(value)
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { latest(it) }
    }
    OutlinedSecureTextField(
        state = state,
        label = { Text(stringResource(label)) },
        isError = isError,
        modifier = modifier.fillMaxWidth(),
    )
}
