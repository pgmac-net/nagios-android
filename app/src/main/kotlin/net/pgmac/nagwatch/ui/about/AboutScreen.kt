// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.about

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pgmac.nagwatch.BuildConfig
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.ui.NagwatchTopAppBar

object AboutTags {
    const val VERSION = "about_version"
    const val SOURCE = "about_source"
    const val ISSUES = "about_issues"
    const val SECURITY = "about_security"
    const val OWN_LICENCE = "about_own_licence"
    const val LIBRARIES = "about_libraries"
}

object LicencesTags {
    const val LIST = "licences_list"
    const val EMPTY = "licences_empty"

    fun library(coordinates: String) = "licences_library_$coordinates"
}

object LicenceTextTags {
    const val TEXT = "licence_text"
}

object AboutLinks {
    const val SOURCE = "https://github.com/pgmac-net/nagios-android"
    const val ISSUES = "$SOURCE/issues"
    const val SECURITY = "$SOURCE/security/policy"
}

/** What this build calls itself. */
data class AppVersion(val name: String, val code: Long, val debug: Boolean) {
    companion object {
        @Suppress("DEPRECATION")
        fun of(context: Context): AppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode
            return AppVersion(info.versionName.orEmpty(), code.toLong(), BuildConfig.DEBUG)
        }
    }
}

/** The libraries in the APK, read off the main thread. Null while that is happening; empty if there is no list. */
@Composable
internal fun rememberLibraries(): State<List<Library>?> {
    val context = LocalContext.current
    return produceState<List<Library>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { Libraries.load(context.assets) }
    }
}

@Composable
fun AboutScreen(onOpenUrl: (String) -> Unit, onOpenLicence: (String) -> Unit, onOpenLibraries: () -> Unit) {
    val context = LocalContext.current
    val libraries by rememberLibraries()
    AboutContent(
        version = AppVersion.of(context),
        libraryCount = libraries?.size,
        onOpenUrl = onOpenUrl,
        onOpenLicence = onOpenLicence,
        onOpenLibraries = onOpenLibraries,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(
    version: AppVersion,
    libraryCount: Int?,
    onOpenUrl: (String) -> Unit,
    onOpenLicence: (String) -> Unit,
    onOpenLibraries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { NagwatchTopAppBar(title = { Text(stringResource(R.string.about_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(version)
            Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_free_software), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_trademark), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            AboutRow(R.string.about_source, AboutTags.SOURCE) { onOpenUrl(AboutLinks.SOURCE) }
            AboutRow(R.string.about_issues, AboutTags.ISSUES) { onOpenUrl(AboutLinks.ISSUES) }
            AboutRow(R.string.about_security, AboutTags.SECURITY) { onOpenUrl(AboutLinks.SECURITY) }
            HorizontalDivider()
            AboutRow(R.string.about_own_licence, AboutTags.OWN_LICENCE) { onOpenLicence(LicenceTexts.NAGWATCH) }
            AboutRow(
                label = libraryCount?.let { stringResource(R.string.about_libraries_count, it) }
                    ?: stringResource(R.string.about_libraries),
                tag = AboutTags.LIBRARIES,
                onClick = onOpenLibraries,
            )
        }
    }
}

@Composable
private fun Header(version: AppVersion) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))
                .background(colorResource(R.color.ic_launcher_background)),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        val text = stringResource(R.string.about_version, version.name, version.code)
        Text(
            text = if (version.debug) stringResource(R.string.about_version_debug, text) else text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(AboutTags.VERSION),
        )
    }
}

@Composable
private fun AboutRow(label: Int, tag: String, onClick: () -> Unit) = AboutRow(stringResource(label), tag, onClick)

@Composable
private fun AboutRow(label: String, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)
            .testTag(tag),
    )
}
