// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import android.net.Uri
import net.pgmac.nagwatch.ui.profile.ProfileEditorViewModel

/**
 * Where everything is. Host and service names are free text chosen by whoever
 * configured Nagios (spaces, slashes, percent signs), so they travel as encoded
 * query arguments, never as path segments.
 */
internal object Routes {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val PROFILE = "profile/{${ProfileEditorViewModel.ARG_PROFILE_ID}}"

    const val ARG_PROFILE = "profile"
    const val ARG_HOST = "host"
    const val ARG_SERVICE = "service"
    const val HOST = "host/{$ARG_PROFILE}?$ARG_HOST={$ARG_HOST}"
    const val SERVICE = "service/{$ARG_PROFILE}?$ARG_HOST={$ARG_HOST}&$ARG_SERVICE={$ARG_SERVICE}"

    /** A non-positive id means "new profile". */
    const val NEW_PROFILE_ID = 0L

    fun profile(id: Long) = "profile/$id"

    fun host(profileId: Long, hostName: String) = "host/$profileId?$ARG_HOST=${Uri.encode(hostName)}"

    fun service(profileId: Long, hostName: String, description: String) =
        "service/$profileId?$ARG_HOST=${Uri.encode(hostName)}&$ARG_SERVICE=${Uri.encode(description)}"
}
