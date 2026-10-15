// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.status.Inventory
import net.pgmac.nagwatch.status.InventoryRepository

/** The configuration behind the grouped views of the Hosts and Services tabs. */
@HiltViewModel
class InventoryViewModel @Inject constructor(private val repository: InventoryRepository) : ViewModel() {
    val inventories: StateFlow<Map<Long, Inventory>> = repository.inventories

    fun loadHostGroups(profileId: Long, force: Boolean) {
        viewModelScope.launch { repository.loadHostGroups(profileId, force) }
    }

    fun loadServiceGroups(profileId: Long, force: Boolean) {
        viewModelScope.launch { repository.loadServiceGroups(profileId, force) }
    }

    /** A pull to refresh reads again whatever the grouped views have read. */
    fun refresh(profileId: Long) {
        viewModelScope.launch { repository.refreshLoaded(profileId) }
    }
}
