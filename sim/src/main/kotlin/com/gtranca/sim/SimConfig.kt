package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.HardBotConfig
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
 * @property hardIterations iterações por decisão do bot `dificil`. Por padrão, na simulação ele roda sem teto
 *   de tempo (orçamento só por iterações), para que os resultados sejam reproduzíveis.
 * @property hardTimeMillis teto de tempo por decisão do `dificil` (como no app), ou `null` (padrão). Com teto,
 *   os resultados dependem da máquina e da carga: use só para medir custo.
 * @property hardRolloutTurns, hardExploration, hardCandidates ajustes finos do `dificil` (ver [HardBotConfig]).
 * @property threads jogos simulados em paralelo (cada jogo é independente; o resultado não muda).
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
    val hardIterations: Int = HardBotConfig.DEFAULT_ITERATIONS,
    val hardTimeMillis: Long? = null,
    val hardRolloutTurns: Int = HardBotConfig.DEFAULT.rolloutTurns,
    val hardExploration: Double = HardBotConfig.DEFAULT.explorationConstant,
    val hardCandidates: Int = HardBotConfig.DEFAULT.candidatesPerKind,
    val hardTreeDepth: Int = HardBotConfig.DEFAULT.treeDepth,
    val hardMargin: Double = HardBotConfig.DEFAULT.overrideMargin,
    val hardMinVisits: Int = HardBotConfig.DEFAULT.minVisitsToOverride,
    val hardConfidence: Double = HardBotConfig.DEFAULT.overrideConfidence,
    val threads: Int = 1,
) {
    init {
        require(games > 0) { "--games deve ser positivo" }
        require(target > 0) { "--target deve ser positivo" }
        require(sides.size == 2) { "--sides deve ter 2 valores (um por lado), ex.: facil,facil" }
        sides.forEach { require(it in BOT_KINDS) { "Bot inválido em --sides: '$it' (use ${BOT_KINDS.joinToString()})" } }
        require(maxActionsPerRound > 0 && maxRoundsPerGame > 0) { "Limites devem ser positivos" }
        require(hardIterations > 0) { "--hard-iterations deve ser positivo" }
        require(hardTimeMillis == null || hardTimeMillis > 0) { "--hard-time-ms deve ser positivo" }
        require(hardRolloutTurns >= 0) { "--hard-rollout-turns não pode ser negativo" }
        require(hardExploration >= 0.0) { "--hard-exploration não pode ser negativa" }
        require(hardCandidates > 0) { "--hard-candidates deve ser positivo" }
        require(hardTreeDepth > 0) { "--hard-tree-depth deve ser positivo" }
        require(hardMargin >= 0.0) { "--hard-margin não pode ser negativa" }
        require(hardMinVisits >= 0) { "--hard-min-visits não pode ser negativo" }
        require(hardConfidence >= 0.0) { "--hard-confidence não pode ser negativa" }
        require(hardExploration.isFinite() && hardMargin.isFinite() && hardConfidence.isFinite()) {
            "--hard-exploration, --hard-margin e --hard-confidence devem ser números finitos"
        }
        require(threads > 0) { "--threads deve ser positivo" }
    }

    /** Configuração do bot `dificil` nesta simulação: [hardIterations] iterações e o teto [hardTimeMillis]. */
    val hardConfig: HardBotConfig get() = HardBotConfig(
            iterations = hardIterations,
            timeLimitMillis = hardTimeMillis,
            explorationConstant = hardExploration,
            candidatesPerKind = hardCandidates,
            rolloutTurns = hardRolloutTurns,
            treeDepth = hardTreeDepth,
            overrideMargin = hardMargin,
            minVisitsToOverride = hardMinVisits,
            overrideConfidence = hardConfidence,
        )

    companion object {
        const val RANDOM_BOT = "aleatorio"
        const val MEDIUM_BASELINE = "medio-base"
        val BOT_KINDS: List<String> = Difficulty.entries.map { it.id } + RANDOM_BOT + MEDIUM_BASELINE

        const val USAGE = """Uso: gradlew :sim:run --args="[opções]"
  --games N            número de jogos (padrão 100)
  --seed S             semente base; o jogo i usa S + i (padrão 42)
  --mode M             individual | duplas (padrão individual)
  --target P           pontuação-alvo (padrão 3000)
  --sides A,B          bot de cada lado: facil, medio, dificil, aleatorio (padrão facil,facil)
  --check-invariants   verificações extras após cada ação (mais lento)
  --max-actions N      limite de ações por partida antes de acusar travamento (padrão 5000)
  --max-rounds N       limite de partidas por jogo antes de acusar travamento (padrão 200)
  --hard-iterations N  iterações por decisão do bot dificil, sem teto de tempo (padrão 20)
  --hard-time-ms N     teto de tempo por decisão do dificil, como no app (padrão: sem teto;
                       com teto os resultados dependem da máquina: use só para medir custo)
  --hard-rollout-turns N  passagens de vez simuladas antes de avaliar (padrão 10)
  --hard-exploration C    constante de exploração do UCT (padrão 0.3)
  --hard-candidates N     candidatas por tipo de ação em cada nó (padrão 2)
  --hard-tree-depth N     ações do próprio assento na árvore, na mesma jogada (padrão 1)
  --hard-margin M         vantagem média mínima para trocar a escolha do Médio (padrão 0.01)
  --hard-min-visits N     visitas mínimas para trocar a escolha do Médio (padrão 5)
  --hard-confidence Z     erros-padrão exigidos da vantagem para trocar a escolha do Médio (padrão 1.0)
  --threads N          jogos simulados em paralelo; não muda os resultados (padrão 1)"""

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
                    "--hard-iterations" -> config = config.copy(hardIterations = int(arg))
                    "--hard-time-ms" -> config = config.copy(hardTimeMillis = value(arg).toLongOrNull() ?: throw IllegalArgumentException("--hard-time-ms deve ser inteiro"))
                    "--hard-rollout-turns" -> config = config.copy(hardRolloutTurns = int(arg))
                    "--hard-exploration" -> config = config.copy(hardExploration = value(arg).toDoubleOrNull() ?: throw IllegalArgumentException("--hard-exploration deve ser número"))
                    "--hard-candidates" -> config = config.copy(hardCandidates = int(arg))
                    "--hard-tree-depth" -> config = config.copy(hardTreeDepth = int(arg))
                    "--hard-margin" -> config = config.copy(hardMargin = value(arg).toDoubleOrNull() ?: throw IllegalArgumentException("--hard-margin deve ser número"))
                    "--hard-min-visits" -> config = config.copy(hardMinVisits = int(arg))
                    "--hard-confidence" -> config = config.copy(hardConfidence = value(arg).toDoubleOrNull() ?: throw IllegalArgumentException("--hard-confidence deve ser número"))
                    "--threads" -> config = config.copy(threads = int(arg))
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

/**
 * Cria o bot do tipo [kind] (ver [SimConfig.BOT_KINDS]) com o RNG [random]. O `dificil` usa [hardConfig]
 * (padrão: o orçamento padrão por iterações, sem teto de tempo, para ser reproduzível).
 */
fun createSimBot(kind: String, random: Random, hardConfig: HardBotConfig = HardBotConfig.DEFAULT.withoutTimeLimit()): BotPlayer =
    when (kind) {
        SimConfig.RANDOM_BOT -> RandomBot(random)
        // Médio sem os ajustes de 2026-10 (3 vermelho na 1ª canastra, topo + carta da mão): referência de comparação.
        SimConfig.MEDIUM_BASELINE -> com.gtranca.ai.MediumBot(random, com.gtranca.ai.MediumWeights(redThreeSwing = 0.0, bridgeTakeFactor = 0.0))
        else -> createBot(Difficulty.fromId(kind), random, hardConfig)
    }
