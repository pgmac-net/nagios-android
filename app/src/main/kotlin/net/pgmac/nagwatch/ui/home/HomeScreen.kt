// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.status.ProfileStatus
import net.pgmac.nagwatch.status.StatusError
import net.pgmac.nagwatch.ui.browse.HostsTab
import net.pgmac.nagwatch.ui.browse.ServicesTab
import net.pgmac.nagwatch.ui.problems.ProblemKind
import net.pgmac.nagwatch.ui.problems.ProblemsTab

object HomeTags {
    const val NO_PROFILES = "home_no_profiles"
    const val ADD_PROFILE = "home_add_profile"
    const val LOADING = "home_loading"
    const val ERROR = "home_error"
    const val BANNER_FAILED = "home_banner_failed"
    const val BANNER_STALE = "home_banner_stale"
    const val BANNER_DEGRADED = "home_banner_degraded"
    const val UPDATED = "home_updated"
    const val PROFILE_MENU = "home_profile_menu"

    fun tab(tab: HomeTab) = "home_tab_${tab.name}"
}

/** What the home screen and its tabs can ask for. */
interface HomeActions {
    fun refresh()

    fun toggleFilter(kind: ProblemKind)

    fun selectProfile(id: Long)

    fun addProfile()

    fun editProfile(id: Long)

    fun manageProfiles()

    fun openHost(hostName: String)

    fun openService(hostName: String, description: String)
}

/** Where the home screen can send the user. */
interface HomeNavigation {
    fun addProfile()

    fun editProfile(id: Long)

    fun manageProfiles()

    fun openHost(profileId: Long, hostName: String)

    fun openService(profileId: Long, hostName: String, description: String)
}

@Composable
fun HomeScreen(navigation: HomeNavigation, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back to the screen refreshes, but only if what is held is old. The first load does
    // not depend on this: the view model loads as soon as it knows which profile is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshIfNeeded() }

    val actions = object : HomeActions {
        override fun refresh() = viewModel.refresh()

        override fun toggleFilter(kind: ProblemKind) = viewModel.toggleFilter(kind)

        override fun selectProfile(id: Long) = viewModel.select(id)

        override fun addProfile() = navigation.addProfile()

        override fun editProfile(id: Long) = navigation.editProfile(id)

        override fun manageProfiles() = navigation.manageProfiles()

        override fun openHost(hostName: String) {
            state.selected?.let { navigation.openHost(it.id, hostName) }
        }

        override fun openService(hostName: String, description: String) {
            state.selected?.let { navigation.openService(it.id, hostName, description) }
        }
    }
    HomeContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    initialTab: HomeTab = HomeTab.PROBLEMS,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Each tab keeps its own search, filter and scroll position while another is shown.
    val tabStates = rememberSaveableStateHolder()
    val selected = state.selected
    // Back from Hosts or Services returns to Problems, where the app opens; from there it leaves.
    BackHandler(enabled = tab != HomeTab.PROBLEMS) { tab = HomeTab.PROBLEMS }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { ProfileSwitcher(state, actions) }) },
        bottomBar = { if (selected != null) HomeTabs(tab, onSelect = { tab = it }) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> Loading()

                selected == null -> NoProfiles(actions)

                // Keyed by profile as well: another profile's hosts need another search.
                else -> tabStates.SaveableStateProvider("${selected.id}:${tab.name}") {
                    ProfileBody(state, selected, tab, actions)
                }
            }
        }
    }
}

private enum class TabItem(val tab: HomeTab, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    PROBLEMS(HomeTab.PROBLEMS, R.string.tab_problems, R.drawable.ic_tab_problems),
    HOSTS(HomeTab.HOSTS, R.string.tab_hosts, R.drawable.ic_tab_hosts),
    SERVICES(HomeTab.SERVICES, R.string.tab_services, R.drawable.ic_tab_services),
}

@Composable
private fun HomeTabs(current: HomeTab, onSelect: (HomeTab) -> Unit) {
    NavigationBar {
        TabItem.entries.forEach { item ->
            NavigationBarItem(
                selected = item.tab == current,
                onClick = { onSelect(item.tab) },
                // The label beside it names the tab; the icon is decoration.
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(stringResource(item.label), maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag(HomeTags.tab(item.tab)),
            )
        }
    }
}

@Composable
private fun ProfileSwitcher(state: HomeUiState, actions: HomeActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(HomeTags.PROFILE_MENU)) {
            Text(
                text = state.selected?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        open = false
                        actions.selectProfile(profile.id)
                    },
                )
            }
            HorizontalDivider()
            state.selected?.let { selected ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.problems_edit_profile)) },
                    onClick = {
                        open = false
                        actions.editProfile(selected.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.problems_manage_profiles)) },
                onClick = {
                    open = false
                    actions.manageProfiles()
                },
            )
        }
    }
}

@Composable
private fun Loading() {
    Text(stringResource(R.string.problems_loading), modifier = Modifier.padding(24.dp).testTag(HomeTags.LOADING))
}

/** First run: nothing to show until a profile exists, so say what to do. */
@Composable
private fun NoProfiles(actions: HomeActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(HomeTags.NO_PROFILES),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.profiles_empty_text))
        Button(onClick = actions::addProfile, modifier = Modifier.testTag(HomeTags.ADD_PROFILE)) {
            Text(stringResource(R.string.profiles_add))
        }
    }
}

/** One profile's status in the chosen tab. Every tab loads, fails and refreshes the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileBody(state: HomeUiState, profile: Profile, tab: HomeTab, actions: HomeActions) {
    val status = state.status
    val refreshing = status?.refreshing == true
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
        when {
            // Nothing fetched yet and nothing to show: the spinner is the pull indicator, add words.
            status?.report == null && status?.error == null -> Loading()

            status.report == null -> FirstFailure(status.error, profile, actions)

            else -> TabBody(tab, state, status, actions)
        }
    }
}

@Composable
private fun TabBody(tab: HomeTab, state: HomeUiState, status: ProfileStatus, actions: HomeActions) {
    when (tab) {
        HomeTab.PROBLEMS -> ProblemsTab(state, status, actions)
        HomeTab.HOSTS -> HostsTab(status, state.now, onOpen = actions::openHost)
        HomeTab.SERVICES -> ServicesTab(status, state.now, onOpen = actions::openService)
    }
}

/** The first fetch failed, so there is no earlier result to fall back on. */
@Composable
private fun FirstFailure(error: StatusError?, profile: Profile, actions: HomeActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(HomeTags.ERROR),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.problems_failed_title), style = MaterialTheme.typography.titleLarge)
        Text(error.message())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions::refresh) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = { actions.editProfile(profile.id) }) {
                Text(stringResource(R.string.problems_edit_profile))
            }
        }
    }
}
