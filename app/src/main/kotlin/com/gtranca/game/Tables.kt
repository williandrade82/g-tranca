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
    val host: Persona,
    val mode: GameMode,
    val difficulty: Difficulty,
    val targetScore: Int,
    val rules: RuleSet,
) {
    fun toConfig(): GameConfig = GameConfig(mode, difficulty, targetScore, rules)
}

object Tables {
    /** §14.4 metas sorteadas nas mesas dos jogadores virtuais. */
    val BOT_TARGETS = listOf(1500, 2000, 3000)

    const val BOT_TABLE_COUNT = 4

    /** Identificador das regras personalizadas do jogador (§14.4). */
    const val CUSTOM_RULES_ID = "minhas-regras"

    /**
     * §14.4 mesas abertas pelos jogadores virtuais para a semente [seed]: cada uma com perfil da casa, modo,
     * dificuldade e meta sorteados. Os perfis da casa se alternam, para os dois sempre aparecerem.
     */
    fun botTables(seed: Long, avoidName: String = ""): List<TableOffer> {
        val random = Random(seed)
        val hosts = PersonaGenerator.opponents(seed, BOT_TABLE_COUNT, avoidName)
        val firstRules = random.nextInt(RuleSet.HOUSE.size)
        return List(BOT_TABLE_COUNT) { i ->
            TableOffer(
                id = i,
                host = hosts[i],
                mode = GameMode.entries[random.nextInt(GameMode.entries.size)],
                difficulty = Difficulty.entries[random.nextInt(Difficulty.entries.size)],
                targetScore = BOT_TARGETS[random.nextInt(BOT_TARGETS.size)],
                rules = RuleSet.HOUSE[(firstRules + i) % RuleSet.HOUSE.size],
            )
        }
    }

    /** Regras personalizadas iniciais (§14.4): cópia da Clássica. */
    fun defaultCustomRules(name: String): RuleSet = RuleSet.CLASSICA.duplicate(CUSTOM_RULES_ID, name)
}
