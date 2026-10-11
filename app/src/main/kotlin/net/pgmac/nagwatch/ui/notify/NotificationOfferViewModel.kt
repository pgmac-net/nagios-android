// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.notify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.notify.NotificationAccess
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.ui.PermissionRequest
import net.pgmac.nagwatch.ui.home.selectProfile

/**
 * Offers notifications for the profile on screen, once, after it has loaded.
 *
 * Notifications are off until switched on, and the switch is in the profile's editor, where
 * few will look. So the app says once that it can, at the moment it has just shown it works.
 * Whatever the answer it does not ask again for that profile.
 */
@HiltViewModel
class NotificationOfferViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    selectedProfile: SelectedProfile,
    statuses: StatusRepository,
    private val access: NotificationAccess,
) : ViewModel() {
    /** The profile Android's prompt was raised for, while its answer is awaited. */
    private var awaiting: Long? = null

    /** The profile to make the offer for, or null when there is nothing to offer. */
    val offerFor: StateFlow<Profile?> =
        combine(profiles.observeProfiles(), selectedProfile.id, statuses.statuses) { all, requested, byProfile ->
            selectProfile(all, requested)?.takeIf { it.isOpenToOffer() && byProfile[it.id].hasJustLoaded() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** "Turn on". Switched on at once if Android already allows notifications, otherwise after it says yes. */
    fun accept(request: PermissionRequest) {
        val id = offerFor.value?.id ?: return
        viewModelScope.launch {
            // Said first, whatever follows: the offer has been made and answered.
            profiles.notificationsOffered(id, accepted = access.isHeld())
            when {
                access.isHeld() -> Unit

                // Android will not show its prompt again. The settings page is the only way, and
                // the switch in the profile's editor is where to come back to.
                access.exhausted() -> request.openSettings()

                else -> {
                    awaiting = id
                    request.askAndroid()
                }
            }
        }
    }

    /** Android's prompt came back. A refusal leaves notifications off, which is what was asked for. */
    fun answered(granted: Boolean, canAskAgain: Boolean) {
        val id = awaiting
        awaiting = null
        viewModelScope.launch {
            access.answered(granted, canAskAgain)
            if (granted && id != null) profiles.notificationsOffered(id, accepted = true)
        }
    }

    /** "Not now", or tapping away. */
    fun decline() {
        val id = offerFor.value?.id ?: return
        viewModelScope.launch { profiles.notificationsOffered(id, accepted = false) }
    }

    private fun Profile.isOpenToOffer(): Boolean = !notifications.enabled && !notificationsOffered

    /** Showing a good result and not in the middle of fetching another: the moment the app has shown it works. */
    private fun ProfileStatus?.hasJustLoaded(): Boolean =
        this != null && lastSuccess != null && error == null && !refreshing

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
