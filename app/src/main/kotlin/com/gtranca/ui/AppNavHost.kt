package com.gtranca.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.ui.game.GameScreen
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.home.HomeScreen
import com.gtranca.ui.home.HomeViewModel
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

/** Jogo novo com a configuração escolhida no início (§14). */
@Serializable
data class GameRoute(val mode: String, val difficulty: String, val targetScore: Int) {
    fun toConfig() = GameConfig(GameMode.valueOf(mode), Difficulty.fromId(difficulty), targetScore)

    companion object {
        fun of(config: GameConfig) = GameRoute(config.mode.name, config.difficulty.id, config.targetScore)
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(viewModel { HomeViewModel() }) { config ->
                navController.navigate(GameRoute.of(config))
            }
        }
        composable<GameRoute> { entry ->
            val config = entry.toRoute<GameRoute>().toConfig()
            GameScreen(viewModel { GameViewModel(config) }) {
                navController.popBackStack(HomeRoute, inclusive = false)
            }
        }
    }
}
