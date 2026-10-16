// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pgmac.nagwatch.R

/** The full text of the licence [spdxId]; says so if the APK has none. */
@Composable
fun LicenceTextScreen(spdxId: String) {
    val resources = LocalResources.current
    val text by produceState<String?>(initialValue = null, spdxId, resources) {
        value = withContext(Dispatchers.IO) {
            LicenceTexts.resourceFor(spdxId)?.let { id ->
                resources.openRawResource(id).use { it.readBytes().decodeToString() }
            }.orEmpty()
        }
    }
    LicenceTextContent(spdxId = spdxId, text = text)
}

/** @param text null while it loads, empty if there is none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenceTextContent(spdxId: String, text: String?, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(spdxId) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding)
                .padding(16.dp),
        ) {
            when {
                text == null -> Unit

                text.isEmpty() -> Text(stringResource(R.string.licences_text_unavailable))

                else -> SelectionContainer {
                    // Licence texts are laid out in columns and hard-wrapped; wrapping them again
                    // makes a mess, so they keep their lines and scroll sideways instead.
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).testTag(LicenceTextTags.TEXT),
                    )
                }
            }
        }
    }
}
