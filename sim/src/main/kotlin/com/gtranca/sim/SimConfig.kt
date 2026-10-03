package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.RandomBot
import com.gtranca.ai.createBot
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import kotlin.random.Random

/**
 * Configuração da simulação (ver skill `simulate-matches`).
 *
 * @property sides tipo de bot de cada lado, indexado por `Side.index` (`facil`, `medio`, `dificil` ou
 *   `aleatorio`, este último uma referência que escolhe ações legais ao acaso).
 * @property seed semente base: o jogo i (0, 1, …) usa a semente `seed + i`.
 */
data class SimConfig(
    val games: Int = 100,
    val seed: Long = 42,
    val mode: GameMode = GameMode.INDIVIDUAL,
    val target: Int = RuleSet.DEFAULT.defaultTargetScore,
    val sides: List<String> = listOf("facil", "facil"),
    val checkInvariants: Boolean = false,
    val maxActionsPerRound: Int = 5_000,
    val maxRoundsPerGame: Int = 200,
) {
    init {
        require(games > 0) { "--games deve ser positivo" }
        require(target > 0) { "--target deve ser positivo" }
        require(sides.size == 2) { "--sides deve ter 2 valores (um por lado), ex.: facil,facil" }
        sides.forEach { require(it in BOT_KINDS) { "Bot inválido em --sides: '$it' (use ${BOT_KINDS.joinToString()})" } }
        require(maxActionsPerRound > 0 && maxRoundsPerGame > 0) { "Limites devem ser positivos" }
    }

    companion object {
        const val RANDOM_BOT = "aleatorio"
        val BOT_KINDS: List<String> = Difficulty.entries.map { it.id } + RANDOM_BOT

        const val USAGE = """Uso: gradlew :sim:run --args="[opções]"
  --games N            número de jogos (padrão 100)
  --seed S             semente base; o jogo i usa S + i (padrão 42)
  --mode M             individual | duplas (padrão individual)
  --target P           pontuação-alvo (padrão 3000)
  --sides A,B          bot de cada lado: facil, medio, dificil, aleatorio (padrão facil,facil)
  --check-invariants   verificações extras após cada ação (mais lento)
  --max-actions N      limite de ações por partida antes de acusar travamento (padrão 5000)
  --max-rounds N       limite de partidas por jogo antes de acusar travamento (padrão 200)"""

        /** Lê os argumentos do CLI. Lança [IllegalArgumentException] com mensagem amigável se inválidos. */
        fun parse(args: Array<String>): SimConfig {
            var config = SimConfig()
            var i = 0
            fun value(name: String): String {
                require(i + 1 < args.size) { "Falta o valor de $name" }
                return args[++i]
            }
            fun int(name: String): Int = value(name).toIntOrNull() ?: throw IllegalArgumentException("$name deve ser inteiro")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--games" -> config = config.copy(games = int(arg))
                    "--seed" -> config = config.copy(seed = value(arg).toLongOrNull() ?: throw IllegalArgumentException("--seed deve ser inteiro"))
                    "--mode" -> config = config.copy(mode = parseMode(value(arg)))
                    "--target" -> config = config.copy(target = int(arg))
                    "--sides" -> config = config.copy(sides = value(arg).split(",").map { it.trim().lowercase() })
                    "--check-invariants" -> config = config.copy(checkInvariants = true)
                    "--max-actions" -> config = config.copy(maxActionsPerRound = int(arg))
                    "--max-rounds" -> config = config.copy(maxRoundsPerGame = int(arg))
                    else -> throw IllegalArgumentException("Opção desconhecida: $arg")
                }
                i++
            }
            return config
        }

        private fun parseMode(text: String): GameMode = when (text.lowercase()) {
            "individual" -> GameMode.INDIVIDUAL
            "duplas" -> GameMode.DUPLAS
            else -> throw IllegalArgumentException("--mode deve ser individual ou duplas: '$text'")
        }
    }
}

/** Cria o bot do tipo [kind] (ver [SimConfig.BOT_KINDS]) com o RNG [random]. */
fun createSimBot(kind: String, random: Random): BotPlayer =
    if (kind == SimConfig.RANDOM_BOT) RandomBot(random) else createBot(Difficulty.fromId(kind), random)
