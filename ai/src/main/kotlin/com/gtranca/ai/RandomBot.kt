package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import kotlin.random.Random

/** Referência para simulações: escolhe uma ação legal qualquer, uniformemente. Não é um nível do jogo. */
class RandomBot(private val random: Random) : BotPlayer {
    override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "Sem ações legais" }
        return legal.random(random)
    }
}
