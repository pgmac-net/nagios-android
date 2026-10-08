// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import javax.inject.Inject
import javax.inject.Singleton

/** Build facts the UI shows. Injected so screens never reach for BuildConfig directly. */
@Singleton
class AppInfo @Inject constructor() {
    val versionName: String = BuildConfig.VERSION_NAME
    val isDebug: Boolean = BuildConfig.DEBUG
}
