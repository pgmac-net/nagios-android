// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for Nagios states. These carry meaning, so they are fixed: they do
 * not follow Material You dynamic colour, only light/dark (design section 8).
 */
@Immutable
data class StateColors(
    val ok: Color,
    val warning: Color,
    val critical: Color,
    val unknown: Color,
    val pending: Color,
    val onState: Color,
)

val LightStateColors = StateColors(
    ok = Color(0xFF2E7D32),
    warning = Color(0xFFF9A825),
    critical = Color(0xFFC62828),
    unknown = Color(0xFFEF6C00),
    pending = Color(0xFF757575),
    onState = Color(0xFFFFFFFF),
)

val DarkStateColors = StateColors(
    ok = Color(0xFF66BB6A),
    warning = Color(0xFFFFD54F),
    critical = Color(0xFFEF5350),
    unknown = Color(0xFFFFA726),
    pending = Color(0xFFBDBDBD),
    onState = Color(0xFF000000),
)

val LocalStateColors = staticCompositionLocalOf { LightStateColors }
