// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.pgmac.nagwatch.ui.profile.ProfileEditorScreen
import net.pgmac.nagwatch.ui.profile.ProfileEditorViewModel
import net.pgmac.nagwatch.ui.profile.ProfilesScreen

private object Routes {
    const val PROFILES = "profiles"
    const val PROFILE = "profile/{${ProfileEditorViewModel.ARG_PROFILE_ID}}"

    /** A non-positive id means "new profile". */
    const val NEW_PROFILE_ID = 0L

    fun profile(id: Long) = "profile/$id"
}

@Composable
fun NagwatchNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROFILES) {
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
    }
}
