// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.pgmac.nagwatch.ui.detail.DetailPlaceholderScreen
import net.pgmac.nagwatch.ui.home.HomeNavigation
import net.pgmac.nagwatch.ui.home.HomeScreen
import net.pgmac.nagwatch.ui.profile.ProfileEditorScreen
import net.pgmac.nagwatch.ui.profile.ProfileEditorViewModel
import net.pgmac.nagwatch.ui.profile.ProfilesScreen

@Composable
fun NagwatchNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(homeNavigation(navController)) }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onAdd = { navController.navigate(Routes.profile(Routes.NEW_PROFILE_ID)) },
                onEdit = { id -> navController.navigate(Routes.profile(id)) },
            )
        }
        composable(
            route = Routes.PROFILE,
            arguments = listOf(navArgument(ProfileEditorViewModel.ARG_PROFILE_ID) { type = NavType.LongType }),
        ) {
            ProfileEditorScreen(onDone = { navController.popBackStack() })
        }
        detailDestinations(onBack = { navController.popBackStack() })
    }
}

/** The detail screens themselves are the next piece of work; the routes and their arguments are real. */
internal fun NavGraphBuilder.detailDestinations(onBack: () -> Unit) {
    composable(
        route = Routes.HOST,
        arguments = listOf(
            navArgument(Routes.ARG_PROFILE) { type = NavType.LongType },
            navArgument(Routes.ARG_HOST) { type = NavType.StringType },
        ),
    ) { entry ->
        DetailPlaceholderScreen(
            hostName = entry.arguments?.getString(Routes.ARG_HOST).orEmpty(),
            service = null,
            onBack = onBack,
        )
    }
    composable(
        route = Routes.SERVICE,
        arguments = listOf(
            navArgument(Routes.ARG_PROFILE) { type = NavType.LongType },
            navArgument(Routes.ARG_HOST) { type = NavType.StringType },
            navArgument(Routes.ARG_SERVICE) { type = NavType.StringType },
        ),
    ) { entry ->
        DetailPlaceholderScreen(
            hostName = entry.arguments?.getString(Routes.ARG_HOST).orEmpty(),
            service = entry.arguments?.getString(Routes.ARG_SERVICE).orEmpty(),
            onBack = onBack,
        )
    }
}

private fun homeNavigation(navController: NavHostController) = object : HomeNavigation {
    override fun addProfile() = navController.navigate(Routes.profile(Routes.NEW_PROFILE_ID))

    override fun editProfile(id: Long) = navController.navigate(Routes.profile(id))

    override fun manageProfiles() = navController.navigate(Routes.PROFILES)

    override fun openHost(profileId: Long, hostName: String) = navController.navigate(Routes.host(profileId, hostName))

    override fun openService(profileId: Long, hostName: String, description: String) =
        navController.navigate(Routes.service(profileId, hostName, description))
}
