// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/** A string resource and its arguments, so view models can choose text without a Context. */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    // The spread is how Android's string formatting takes arguments; these arrays hold a value or two.
    @Suppress("SpreadOperator")
    @Composable
    @ReadOnlyComposable
    fun resolve(): String = stringResource(id, *args.toTypedArray())
}
