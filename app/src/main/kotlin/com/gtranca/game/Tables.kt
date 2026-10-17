package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import kotlin.random.Random

/**
 * Mesa aberta por um jogador virtual (§14.4): perfil da casa, modo, dificuldade e pontuação-alvo sorteados.
 *
 * @property host quem abriu a mesa (só apresentação).
 */
data class TableOffer(
    val id: Int,
    /** §14.4, §14.5 semente da mesa: o jogo que nasce dela usa a mesma, então joga com exatamente os [players]. */
    val seed: Long,
    /** §14.5 personagens que ocupam a mesa, na ordem dos assentos (1 = adversário à direita; em duplas 2 = parceiro, 3 = adversário à esquerda). */
    val players: List<Persona>,
    val mode: GameMode,
    val difficulty: Difficulty,
    val targetScore: Int,
    val rules: RuleSet,
) {
    /** Quem abriu a mesa (só apresentação): o primeiro personagem. */
    val host: Persona get() = players.first()

    fun toConfig(): GameConfig = GameConfig(mode, difficulty, targetScore, rules)
}

object Tables {
    /** §14.4 metas sorteadas nas mesas dos jogadores virtuais. */
    val BOT_TARGETS = listOf(1500, 2000, 3000)

    const val BOT_TABLE_COUNT = 4

    private const val MAX_HOST_RETRIES = 20

    /** Identificador das regras personalizadas do jogador (§14.4). */
    const val CUSTOM_RULES_ID = "minhas-regras"

    /**
     * §14.4 mesas abertas pelos jogadores virtuais para a semente [seed]: cada uma com perfil da casa, modo,
     * dificuldade e meta sorteados. Os perfis da casa se alternam, para os dois sempre aparecerem.
     */
    fun botTables(seed: Long, avoidName: String = ""): List<TableOffer> {
        val random = Random(seed)
        val firstRules = random.nextInt(RuleSet.HOUSE.size)
        val usedHosts = mutableSetOf<String>()
        return List(BOT_TABLE_COUNT) { i ->
            val mode = GameMode.entries[random.nextInt(GameMode.entries.size)]
            val difficulty = Difficulty.entries[random.nextInt(Difficulty.entries.size)]
            // §14.5 os personagens são sorteados já aqui, com a semente da mesa, no nível da dificuldade dela; os
            // anfitriões de mesas diferentes não repetem o primeiro nome.
            var tableSeed = random.nextLong()
            var players = PersonaGenerator.opponents(tableSeed, mode.seatCount - 1, difficulty, avoidName)
            var attempts = 0
            while (players.first().firstName in usedHosts && attempts++ < MAX_HOST_RETRIES) {
                tableSeed = random.nextLong()
                players = PersonaGenerator.opponents(tableSeed, mode.seatCount - 1, difficulty, avoidName)
            }
            usedHosts += players.first().firstName
            TableOffer(
                id = i,
                seed = tableSeed,
                players = players,
                mode = mode,
                difficulty = difficulty,
                targetScore = BOT_TARGETS[random.nextInt(BOT_TARGETS.size)],
                rules = RuleSet.HOUSE[(firstRules + i) % RuleSet.HOUSE.size],
            )
        }
    }

    /** Regras personalizadas iniciais (§14.4): cópia da Clássica. */
    fun defaultCustomRules(name: String): RuleSet = RuleSet.CLASSICA.duplicate(CUSTOM_RULES_ID, name)
}
