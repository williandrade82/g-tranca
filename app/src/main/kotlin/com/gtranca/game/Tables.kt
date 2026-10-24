package com.gtranca.game

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat

object Tables {
    /** Identificador das regras personalizadas do jogador (§14.4). */
    const val CUSTOM_RULES_ID = "minhas-regras"

    /** Regras personalizadas iniciais (§14.4): cópia da Clássica. */
    fun defaultCustomRules(name: String): RuleSet = RuleSet.CLASSICA.duplicate(CUSTOM_RULES_ID, name)

    /** §14.5 todos os personagens (ids), a fonte do sorteio dos que sobram na mesa. */
    val personaPool: List<String> get() = BotProfile.entries.map { it.code }

    /**
     * §4.1, §16.2 robôs da mesa só com robôs vistos a partir do jogador: o jogo local põe o jogador sempre no assento 0, e
     * o primeiro jogador de cada jogo é sorteado (§4.1), então só a posição **relativa** escolhida importa. [robots] é o
     * personagem de cada assento da escolha de posições e [humanSeat] o assento do jogador nela; devolve o assento no jogo
     * (relativo ao jogador) → personagem.
     */
    fun relativeRobots(mode: GameMode, humanSeat: Seat, robots: Map<Int, String>): Map<Int, String> =
        robots.mapKeys { (index, _) -> Math.floorMod(index - humanSeat.index, mode.seatCount) }
}
