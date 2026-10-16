// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.ui.NagwatchTopAppBar

@Composable
fun LicencesScreen(onOpenLicence: (String) -> Unit) {
    val libraries by rememberLibraries()
    LicencesContent(libraries = libraries, onOpenLicence = onOpenLicence)
}

/** @param libraries null while loading, empty if the build left no list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesContent(libraries: List<Library>?, onOpenLicence: (String) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { NagwatchTopAppBar(title = { Text(stringResource(R.string.licences_title)) }) },
    ) { innerPadding ->
        when {
            libraries == null -> Unit

            libraries.isEmpty() -> Text(
                stringResource(R.string.licences_unavailable),
                modifier = Modifier.padding(innerPadding).padding(16.dp).testTag(LicencesTags.EMPTY),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).testTag(LicencesTags.LIST)) {
                item {
                    Text(
                        stringResource(R.string.licences_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(libraries, key = { "${it.coordinates}:${it.version}" }) { library ->
                    LibraryRow(library, onOpenLicence)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(library: Library, onOpenLicence: (String) -> Unit) {
    // A licence with no text in the APK cannot be opened; the row still says what it is.
    val id = library.licenceId?.takeIf { LicenceTexts.resourceFor(it) != null }
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (id != null) Modifier.clickable { onOpenLicence(id) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(LicencesTags.library(library.coordinates)),
    ) {
        Text(library.name, style = MaterialTheme.typography.titleMedium)
        Text("${library.coordinates} ${library.version}", style = MaterialTheme.typography.bodySmall)
        Text(library.licenceName, style = MaterialTheme.typography.bodyMedium)
    }
}
