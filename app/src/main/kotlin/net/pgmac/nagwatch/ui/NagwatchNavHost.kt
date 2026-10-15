// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.ui.detail.DetailNavigation
import net.pgmac.nagwatch.ui.detail.DetailScreen
import net.pgmac.nagwatch.ui.graph.GraphViewerScreen
import net.pgmac.nagwatch.ui.graph.rememberScreenTurn
import net.pgmac.nagwatch.ui.home.HomeNavigation
import net.pgmac.nagwatch.ui.home.HomeScreen
import net.pgmac.nagwatch.ui.profile.ProfileEditorScreen
import net.pgmac.nagwatch.ui.profile.ProfileEditorViewModel
import net.pgmac.nagwatch.ui.profile.ProfilesScreen
import net.pgmac.nagwatch.ui.settings.SettingsScreen

/**
 * @param target where a tapped notification asked to go; [onTargetOpened] is called once it has been acted on.
 */
@Composable
fun NagwatchNavHost(target: NotificationTarget? = null, onTargetOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current
    LaunchedEffect(target) {
        if (target != null) {
            navController.open(target)
            onTargetOpened()
        }
    }
    // Here and not in the viewer: the screen is stood up again after the viewer has gone.
    val screenTurn = rememberScreenTurn()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(homeNavigation(navController)) }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onAdd = { navController.navigate(Routes.profile(Routes.NEW_PROFILE_ID)) },
                onEdit = { id -> navController.navigate(Routes.profile(id)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(
            route = Routes.PROFILE,
            arguments = listOf(navArgument(ProfileEditorViewModel.ARG_PROFILE_ID) { type = NavType.LongType }),
        ) {
            ProfileEditorScreen(onDone = { navController.popBackStack() })
        }
        // The view model reads the same arguments itself; the screen needs nothing passed in.
        detailDestinations { _, _ -> DetailScreen(detailNavigation(navController) { openInBrowser(context, it) }) }
        // A service's graph with the screen to itself. Its view model reads the same arguments.
        composable(
            route = Routes.GRAPH,
            arguments = listOf(
                navArgument(Routes.ARG_PROFILE) { type = NavType.LongType },
                navArgument(Routes.ARG_HOST) { type = NavType.StringType },
                navArgument(Routes.ARG_SERVICE) { type = NavType.StringType },
            ),
        ) {
            GraphViewerScreen(screenTurn, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Goes where a notification pointed: a host, a service, or back to the tabs for the Problems
 * list. Whatever was open is left underneath, so Back returns to it.
 */
internal fun NavHostController.open(target: NotificationTarget) {
    val ref = target.ref
    val description = ref?.description
    when {
        ref == null -> popBackStack(Routes.HOME, inclusive = false)
        description == null -> navigate(Routes.host(target.profileId, ref.hostName)) { launchSingleTop = true }
        else -> navigate(Routes.service(target.profileId, ref.hostName, description)) { launchSingleTop = true }
    }
}

/**
 * The host and service destinations. What is shown there is passed in, so the
 * routes and their arguments can be exercised without the rest of the app.
 */
internal fun NavGraphBuilder.detailDestinations(content: @Composable (profileId: Long, ref: ObjectRef) -> Unit) {
    composable(
        route = Routes.HOST,
        arguments = listOf(
            navArgument(Routes.ARG_PROFILE) { type = NavType.LongType },
            navArgument(Routes.ARG_HOST) { type = NavType.StringType },
        ),
    ) { entry ->
        val arguments = entry.arguments
        content(arguments?.getLong(Routes.ARG_PROFILE) ?: 0, ObjectRef(arguments?.getString(Routes.ARG_HOST).orEmpty()))
    }
    composable(
        route = Routes.SERVICE,
        arguments = listOf(
            navArgument(Routes.ARG_PROFILE) { type = NavType.LongType },
            navArgument(Routes.ARG_HOST) { type = NavType.StringType },
            navArgument(Routes.ARG_SERVICE) { type = NavType.StringType },
        ),
    ) { entry ->
        val arguments = entry.arguments
        content(
            arguments?.getLong(Routes.ARG_PROFILE) ?: 0,
            ObjectRef(
                hostName = arguments?.getString(Routes.ARG_HOST).orEmpty(),
                description = arguments?.getString(Routes.ARG_SERVICE).orEmpty(),
            ),
        )
    }
}

private fun detailNavigation(navController: NavHostController, openUrl: (String) -> Unit) = object : DetailNavigation {
    override fun back() {
        navController.popBackStack()
    }

    override fun openHost(profileId: Long, hostName: String) = navController.navigate(Routes.host(profileId, hostName))

    override fun openService(profileId: Long, hostName: String, description: String) =
        navController.navigate(Routes.service(profileId, hostName, description))

    override fun openInBrowser(url: String) = openUrl(url)

    override fun openGraph(profileId: Long, hostName: String, description: String) =
        navController.navigate(Routes.graph(profileId, hostName, description)) { launchSingleTop = true }
}

/**
 * Hands a web page to the browser. If the device has nothing that opens web
 * pages there is nothing useful to do, and crashing is not it.
 */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}

private fun homeNavigation(navController: NavHostController) = object : HomeNavigation {
    override fun addProfile() = navController.navigate(Routes.profile(Routes.NEW_PROFILE_ID))

    override fun editProfile(id: Long) = navController.navigate(Routes.profile(id))

    override fun manageProfiles() = navController.navigate(Routes.PROFILES)

    override fun openSettings() = navController.navigate(Routes.SETTINGS)

    override fun openHost(profileId: Long, hostName: String) = navController.navigate(Routes.host(profileId, hostName))

    override fun openService(profileId: Long, hostName: String, description: String) =
        navController.navigate(Routes.service(profileId, hostName, description))
}
