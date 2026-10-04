package com.gtranca.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameData
import com.gtranca.data.SavedGame
import com.gtranca.engine.model.GameMode
import com.gtranca.game.DataGamePersistence
import com.gtranca.game.GameConfig
import com.gtranca.game.HandSort
import com.gtranca.game.toConfig
import com.gtranca.game.toRestored
import com.gtranca.ui.game.GameScreen
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.home.HomeScreen
import com.gtranca.ui.home.HomeViewModel
import com.gtranca.ui.stats.StatsScreen
import com.gtranca.ui.stats.StatsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object StatsRoute

/**
 * Jogo: novo, com a configuração escolhida no início (§14), ou retomado do jogo salvo ([resume]; aí a configuração
 * vem do arquivo).
 */
@Serializable
data class GameRoute(val mode: String, val difficulty: String, val targetScore: Int, val resume: Boolean = false) {
    fun toConfig() = GameConfig(GameMode.valueOf(mode), Difficulty.fromId(difficulty), targetScore)

    companion object {
        fun of(config: GameConfig) = GameRoute(config.mode.name, config.difficulty.id, config.targetScore)

        /** Continuar o jogo salvo (os campos de configuração não são usados). */
        val RESUME = GameRoute(GameMode.INDIVIDUAL.name, Difficulty.MEDIO.id, 1, resume = true)
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val data = remember { GameData.get(context) }
    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel { HomeViewModel(data.settings, data.savedGames) },
                onStart = { config -> navController.navigate(GameRoute.of(config)) },
                onContinue = { navController.navigate(GameRoute.RESUME) },
                onStats = { navController.navigate(StatsRoute) },
            )
        }
        composable<GameRoute> { entry -> GameDestination(entry.toRoute(), data, navController) }
        composable<StatsRoute> {
            StatsScreen(viewModel { StatsViewModel(data.stats) }) { navController.popBackStack() }
        }
    }
}

private sealed interface GameLoad {
    data object Loading : GameLoad

    /** [resumed]: o carregamento foi de um jogo a retomar (então [saved] `null` = o jogo já terminou). */
    data class Ready(val saved: SavedGame?, val sort: HandSort, val resumed: Boolean) : GameLoad
}

/**
 * Tela do jogo. Depois de criado, o jogo fica marcado (em estado salvável) como "em andamento": se o Android matar o
 * processo e recriar esta tela, o jogo é retomado do arquivo salvo — nunca começa outro. Se o salvo não existir mais
 * (o jogo terminou), volta ao início.
 */
@Composable
private fun GameDestination(route: GameRoute, data: GameData, navController: NavHostController) {
    var resume by rememberSaveable { mutableStateOf(route.resume) }
    val load by produceState<GameLoad>(GameLoad.Loading) {
        val resumed = resume
        val saved = if (resumed) data.savedGames.load() else null
        val sort = data.settings.settings.first().handSortId
            ?.let { id -> HandSort.entries.firstOrNull { it.name == id } } ?: HandSort.CUSTOM
        value = GameLoad.Ready(saved, sort, resumed)
    }
    val goHome = { navController.popBackStack(HomeRoute, inclusive = false) }
    when (val ready = load) {
        GameLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is GameLoad.Ready -> {
            val saved = ready.saved
            if (ready.resumed && saved == null) {
                LaunchedEffect(Unit) { goHome() }
            } else {
                val viewModel = viewModel {
                    val persistence = DataGamePersistence(data)
                    if (saved != null) {
                        GameViewModel(
                            saved.config.toConfig(),
                            gameSeed = saved.gameSeed,
                            restored = saved.toRestored(),
                            gameId = saved.gameId,
                            persistence = persistence,
                            initialSort = ready.sort,
                        )
                    } else {
                        GameViewModel(route.toConfig(), persistence = persistence, initialSort = ready.sort)
                    }
                }
                LaunchedEffect(Unit) { resume = true }
                GameScreen(viewModel) { goHome() }
            }
        }
    }
}
