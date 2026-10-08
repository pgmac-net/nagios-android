// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF455A64),
    tertiary = Color(0xFF00695C),
)

private val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFF80CBC4),
)

/**
 * App theme. Uses Material You dynamic colour on Android 12+, a static scheme
 * below that. State colours are supplied separately and are never dynamic.
 */
@Composable
fun NagwatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> FallbackDarkColors

        else -> FallbackLightColors
    }

    CompositionLocalProvider(LocalStateColors provides if (darkTheme) DarkStateColors else LightStateColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

object NagwatchTheme {
    val stateColors: StateColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStateColors.current
}
