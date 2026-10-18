package edu.ytu.cdtp.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import edu.ytu.cdtp.ui.calibration.CalibrationScreen
import edu.ytu.cdtp.ui.connect.ConnectScreen
import edu.ytu.cdtp.ui.history.HistoryScreen
import edu.ytu.cdtp.ui.home.HomeScreen
import edu.ytu.cdtp.ui.livetest.LiveTestScreen
import edu.ytu.cdtp.ui.result.ResultScreen
import edu.ytu.cdtp.ui.settings.SettingsScreen
import edu.ytu.cdtp.ui.taskselect.TaskSelectScreen

@Composable
fun AppNav(openCvOk: Boolean) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    // Animasyon yok: iki kamera ekranı aynı anda yaşamasın.
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("home") { HomeScreen(openCvOk = openCvOk, go = { nav.navigate(it) }) }
        composable("connect") { ConnectScreen(onBack = back) }
        composable("calibration") { CalibrationScreen(onBack = back) }
        composable("tasks") { TaskSelectScreen(onBack = back, onContinue = { nav.navigate("live") }) }
        composable("live") {
            LiveTestScreen(onBack = back, onDone = { id ->
                nav.navigate("result/$id") { popUpTo("tasks") }
            })
        }
        composable("result/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ResultScreen(
                id = entry.arguments?.getLong("id") ?: 0,
                onBack = back,
                onNewTest = { nav.navigate("tasks") { popUpTo("home") } },
            )
        }
        composable("history") { HistoryScreen(onBack = back, onOpen = { nav.navigate("result/$it") }) }
        composable("settings") { SettingsScreen(onBack = back) }
    }
}
