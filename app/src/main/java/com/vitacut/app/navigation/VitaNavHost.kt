package com.vitacut.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vitacut.feature.editor.EditorScreen
import com.vitacut.feature.export.ExportScreen
import com.vitacut.feature.home.HomeScreen
import com.vitacut.feature.settings.SettingsScreen
import com.vitacut.feature.templates.TemplatesScreen

/** Top-level routes. Feature modules stay navigation-free; the app owns the graph. */
object Routes {
    const val HOME = "home"
    const val TEMPLATES = "templates"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{projectId}"
    const val EXPORT = "export/{projectId}"

    fun editor(projectId: String) = "editor/$projectId"
    fun export(projectId: String) = "export/$projectId"
}

@Composable
fun VitaNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
                onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.TEMPLATES) {
            TemplatesScreen(
                onBack = { navController.popBackStack() },
                onProjectCreated = { id ->
                    navController.navigate(Routes.editor(id)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) {
            EditorScreen(
                onExit = { navController.popBackStack() },
                onExport = { id -> navController.navigate(Routes.export(id)) },
            )
        }
        composable(
            route = Routes.EXPORT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) {
            ExportScreen(onBack = { navController.popBackStack() })
        }
    }
}
