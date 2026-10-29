package com.gtranca.online.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MatchRunnerTest {

    private fun run(mode: GameMode, seed: Long, difficulty: Difficulty = Difficulty.FACIL, target: Int? = null) =
        MatchRunner(MatchConfig(mode = mode, difficulty = difficulty, seed = seed, targetScore = target)).run()

    @Test
    fun `13 jogo completo com semente fixa termina com vencedor e e reproduzivel`() {
        for (mode in GameMode.entries) {
            val a = run(mode, 7)
            a.failure shouldBe null
            a.finished shouldBe true // §13 alguem atingiu a pontuacao-alvo
            (a.totals.max() >= 3000) shouldBe true
            (a.rounds >= 1) shouldBe true
            run(mode, 7) shouldBe a // mesma semente, mesmo resultado
        }
    }

    @Test
    fun `13 sementes diferentes dao jogos diferentes`() {
        (0L until 5L).map { run(GameMode.INDIVIDUAL, it).totals }.toSet().size shouldBe 5
    }

    @Test
    fun `13 Medio e Dificil tambem terminam e sao reproduziveis (Dificil sem teto de tempo)`() {
        for (d in listOf(Difficulty.MEDIO, Difficulty.DIFICIL)) {
            val a = run(GameMode.INDIVIDUAL, 3, d, target = 400) // alvo menor: o Dificil busca por decisao e e lento
            a.finished shouldBe true
            run(GameMode.INDIVIDUAL, 3, d, target = 400) shouldBe a
        }
    }

    @Test
    fun `1 e 11-13 200 jogos individual e duplas sem estado ilegal nem excecao`() {
        for (mode in GameMode.entries) {
            val results = (0L until 200L).map { run(mode, 1_000 + it) }
            results.filter { it.failure != null }.map { it.failure } shouldBe emptyList()
            results.count { it.finished } shouldBe 200
        }
    }

    @Test
    fun `11 nenhuma jogada fora de legalActions e o robo so recebe o PlayerView do assento da vez`() {
        for (mode in GameMode.entries) {
            var checked = 0
            val config = MatchConfig(mode = mode, seed = 11)
            val runner = MatchRunner(
                config,
                botFactory = { seat, random -> ViewCheckingBot(seat, createBot(Difficulty.FACIL, random)) { checked++ } },
                legalActionsOf = { state, seat -> RoundEngine.legalActions(state, seat, config.rules) },
            )
            val result = runner.run()
            result.failure shouldBe null
            (checked > 100) shouldBe true
        }
    }

    @Test
    fun `11 acao fora de legalActions e detectada e acusada`() {
        val config = MatchConfig(seed = 5)
        val result = MatchRunner(
            config,
            botFactory = { _, _ -> object : BotPlayer {
                override fun chooseAction(view: PlayerView, legal: List<Action>): Action = Action.DrawFromStock.takeIf { it !in legal } ?: Action.Discard(view.hand.first())
            } },
            legalActionsOf = { state, seat -> RoundEngine.legalActions(state, seat, config.rules) },
        ).run()
        result.failure?.kind shouldBe "ILLEGAL_ACTION"
    }

    /** Verifica, a cada decisao, que o robo recebeu a vista do SEU assento e que a acao escolhida e legal. */
    private class ViewCheckingBot(val seat: Seat, val inner: BotPlayer, val onCheck: () -> Unit) : BotPlayer {
        override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
            view.seat shouldBe seat
            view.currentSeat shouldBe seat
            view.hand.size shouldBe view.handSizes[seat.index] // so a propria mao em cartas; as demais, so tamanhos
            val action = inner.chooseAction(view, legal)
            legal shouldContain action
            onCheck()
            return action
        }
        override fun observe(event: com.gtranca.engine.PublicEvent) = inner.observe(event)
        override fun onNewRound() = inner.onNewRound()
    }
}
