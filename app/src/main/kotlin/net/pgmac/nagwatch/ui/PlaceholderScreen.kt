// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.ui.theme.NagwatchTheme

/** The only screen in the scaffold: proves theme, resources and DI are wired. Replaced in M1. */
@Composable
fun PlaceholderScreen(versionName: String, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(text = stringResource(R.string.placeholder_tagline), style = MaterialTheme.typography.titleMedium)
            StateSwatches()
            Text(
                text = stringResource(R.string.placeholder_status),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.placeholder_version, versionName),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(PlaceholderScreenTags.VERSION),
            )
        }
    }
}

@Composable
private fun StateSwatches(modifier: Modifier = Modifier) {
    val colors = NagwatchTheme.stateColors
    Row(
        modifier = modifier.testTag(PlaceholderScreenTags.STATE_SWATCHES),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(colors.ok, colors.warning, colors.critical, colors.unknown, colors.pending).forEach { color ->
            Swatch(color)
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(color = color, shape = CircleShape),
    )
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    NagwatchTheme(dynamicColor = false) {
        PlaceholderScreen(versionName = "0.1.0")
    }
}
