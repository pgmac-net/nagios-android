// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.ServiceGroup
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SettingsResult

/** Something read from Nagios' configuration: what was last read, and how the latest read went. */
data class ConfigPart<T>(
    /** Null until it has been read once. Survives a failed refresh. */
    val value: T? = null,
    val loading: Boolean = false,
    val error: StatusError? = null,
)

/** What is known of one profile's configuration. */
data class Inventory(
    val hostGroups: ConfigPart<List<HostGroup>> = ConfigPart(),
    val serviceGroups: ConfigPart<List<ServiceGroup>> = ConfigPart(),
)

/**
 * How each Nagios is configured, for the grouped views of the Hosts and Services tabs
 * (pgmac-net/nagios-android#82).
 *
 * Held in memory only, per profile, for as long as the app runs. Configuration changes when
 * someone edits it, not at every check, so it is read when a view first needs it and again on
 * a pull to refresh. It is not saved: the status cache (docs/adr/0005) is for status, and
 * a view that cannot reach Nagios has no status to group either.
 */
@Singleton
class InventoryRepository @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: NagiosClientProvider,
) {
    private val state = MutableStateFlow<Map<Long, Inventory>>(emptyMap())
    val inventories: StateFlow<Map<Long, Inventory>> = state.asStateFlow()

    /** Reads the host groups unless they are known already ([force] reads them anyway). */
    suspend fun loadHostGroups(profileId: Long, force: Boolean = false) =
        load(profileId, force, { it.hostGroups }, { inv, part -> inv.copy(hostGroups = part) }) {
            it.fetchHostGroups()
        }

    suspend fun loadServiceGroups(profileId: Long, force: Boolean = false) =
        load(profileId, force, { it.serviceGroups }, { inv, part -> inv.copy(serviceGroups = part) }) {
            it.fetchServiceGroups()
        }

    /** A pull to refresh: whatever this profile has read before is read again; nothing new is. */
    suspend fun refreshLoaded(profileId: Long) {
        val known = state.value[profileId] ?: return
        if (known.hostGroups.value != null || known.hostGroups.error != null) loadHostGroups(profileId, force = true)
        if (known.serviceGroups.value != null || known.serviceGroups.error != null) {
            loadServiceGroups(profileId, force = true)
        }
    }

    private suspend fun <T> load(
        profileId: Long,
        force: Boolean,
        part: (Inventory) -> ConfigPart<T>,
        with: (Inventory, ConfigPart<T>) -> Inventory,
        fetch: suspend (NagiosClient) -> NagiosResult<T>,
    ) {
        val held = part(state.value[profileId] ?: Inventory())
        // One read at a time, and none for what is already known unless asked.
        if (held.loading || (!force && held.value != null)) return
        set(profileId, with, held.copy(loading = true))

        val outcome = when (val settings = profiles.settingsFor(profileId)) {
            is SettingsResult.Ready -> when (val result = fetch(clients.create(settings.settings))) {
                is NagiosResult.Success -> ConfigPart(value = result.value)
                is NagiosResult.Failure -> held.copy(error = StatusError.Nagios(result.error))
            }

            SettingsResult.NotFound -> held.copy(error = StatusError.ProfileMissing)

            SettingsResult.CredentialsUnavailable -> held.copy(error = StatusError.CredentialsUnavailable)

            SettingsResult.InvalidUrl -> held.copy(error = StatusError.InvalidUrl)
        }
        set(profileId, with, outcome.copy(loading = false))
    }

    private fun <T> set(profileId: Long, with: (Inventory, ConfigPart<T>) -> Inventory, part: ConfigPart<T>) =
        state.update { all -> all + (profileId to with(all[profileId] ?: Inventory(), part)) }
}
