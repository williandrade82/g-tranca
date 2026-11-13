package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.DealGuess
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Guess
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * §3.6 "Distribuição certeira": minijogo de palpite antes da 1ª vez, que não altera a distribuição real
 * (§3.1/§3.2); só rende bônus de pontos (§12.1). Testes do motor (`:core-engine`); o palpite dos robôs
 * (§14.5) e a interface (modal de seleção) ficam para outras tarefas.
 */
class DealGuessTest {

    // ---------- §3.6 quem palpita e a fase inicial ----------

    @Test
    fun `duplas entra na fase de palpite da mao com o anterior ao primeiro jogador`() {
        // §3.6 jogador 2 (anterior ao que abre, §4.2) palpita a mão primeiro; jogador 3 (anterior a esse) o morto
        val state = dealRound(GameMode.DUPLAS, Random(1), firstSeat = Seat(0))
        state.phase shouldBe Phase.DEAL_GUESS_HAND
        state.currentSeat shouldBe Seat(3)
        state.dealGuess shouldBe DealGuess(dealerSeat = Seat(3), mortoDealerSeat = Seat(2))
    }

    @Test
    fun `individual o jogador que abre a partida acumula os dois papeis`() {
        // §3.6 em individual (2 assentos), a dupla aplicação de "anterior" volta ao próprio assento que abre:
        // ele palpita a mão e, em seguida, o morto, um atrás do outro (decisão explícita do pedido desta regra)
        val state = dealRound(GameMode.INDIVIDUAL, Random(1), firstSeat = Seat(0))
        state.phase shouldBe Phase.DEAL_GUESS_HAND
        state.currentSeat shouldBe Seat(0)
        state.dealGuess shouldBe DealGuess(dealerSeat = Seat(0), mortoDealerSeat = Seat(0))

        val afterHand = RoundEngine.apply(state, Seat(0), Action.GuessDealRange(5, 27)) // largura 22 = alvo individual
        afterHand.phase shouldBe Phase.DEAL_GUESS_MORTO
        afterHand.currentSeat shouldBe Seat(0) // o mesmo assento, "um atrás do outro"
        afterHand.dealGuess?.hand shouldBe Guess(5, 27, correct = true)
    }

    // ---------- §3.6 acerto e erro do palpite da mão ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `palpite da mao acerta com largura igual a cardsPerHand vezes o numero de assentos`(mode: GameMode) {
        // §3.6 alvo da mão: 44 em duplas, 22 em individual (11 cartas por jogador, §3.1)
        val target = RuleSet.DEFAULT.cardsPerHand * mode.seatCount
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, Action.GuessDealRange(10, 10 + target))
        resolved.dealGuess?.hand shouldBe Guess(10, 10 + target, correct = true)
        resolved.phase shouldBe Phase.DEAL_GUESS_MORTO
    }

    @Test
    fun `palpite da mao com largura errada nao acerta mas resolve normalmente`() {
        // §3.6 largura diferente do alvo: palpite registrado, sem acerto, segue para o palpite do morto
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, Action.GuessDealRange(0, 10)) // largura 10 != 44
        resolved.dealGuess?.hand shouldBe Guess(0, 10, correct = false)
        resolved.phase shouldBe Phase.DEAL_GUESS_MORTO
        resolved.currentSeat shouldBe state.dealGuess.mortoDealerSeat
    }

    @Test
    fun `start e end trocados sao normalizados pelo motor`() {
        // §3.6 o motor só confere a largura normalizada; quem resolve a dinâmica de toque é a UI (fora do motor)
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, Action.GuessDealRange(60, 16)) // "fim" antes do "início"
        resolved.dealGuess?.hand shouldBe Guess(16, 60, correct = true) // largura 44
    }

    // ---------- §3.6 acerto e erro do palpite do morto ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `palpite do morto acerta com largura 22`(mode: GameMode) {
        // §3.6 alvo do morto: 22 (11 cartas por morto x 2 mortos, §3.2), em qualquer modo
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.dealGuess!!.dealerSeat, Action.GuessDealRange(0, 10))
        val mortoDealer = afterHand.dealGuess!!.mortoDealerSeat
        val resolved = RoundEngine.apply(afterHand, mortoDealer, Action.GuessDealRange(3, 25))
        resolved.dealGuess?.morto shouldBe Guess(3, 25, correct = true)
        // §3.6 só depois do 2º palpite resolvido a vez do primeiro jogador começa de verdade (§3.5)
        resolved.phase shouldBe Phase.AWAITING_DRAW
        resolved.currentSeat shouldBe state.firstSeat
        resolved.turnsBegun shouldBe 1
    }

    // ---------- §3.6 / §12.1 bônus de pontos ----------

    /** Partida encerrada sem vencedor, com os dois mortos pegos e sem outros bônus de mesa, para isolar o §3.6. */
    private fun finishedRound(mode: GameMode, dealGuess: DealGuess) = round(mode) {
        mortoTaken(0, 0)
        mortoTaken(1, 1)
        result = RoundResult.NoWinner
    }.copy(dealGuess = dealGuess)

    @Test
    fun `duplas acertar a mao e o morto rende 100 pontos para o lado de cada acertador`() {
        // §3.6 / §12.1 jogador 2 (assento 3, lado 1) acerta a mão; jogador 3 (assento 2, lado 0) acerta o morto:
        // lados diferentes, cada um com seu próprio bônus de 100
        val state = finishedRound(
            GameMode.DUPLAS,
            DealGuess(
                dealerSeat = Seat(3),
                mortoDealerSeat = Seat(2),
                hand = Guess(0, 44, correct = true),
                morto = Guess(0, 22, correct = true),
            ),
        )
        val scores = scoreRound(state)
        scores[GameMode.DUPLAS.sideOf(Seat(3)).index].dealGuessBonus shouldBe 100 // lado 1: acertou a mão
        scores[GameMode.DUPLAS.sideOf(Seat(2)).index].dealGuessBonus shouldBe 100 // lado 0: acertou o morto
    }

    @Test
    fun `os dois acertos do mesmo lado somam 200`() {
        // §3.6 / §12.1 "podem ser o mesmo lado": com os dois guessers do mesmo lado, os dois bônus se somam
        val state = finishedRound(
            GameMode.DUPLAS,
            DealGuess(
                dealerSeat = Seat(0), // lado 0
                mortoDealerSeat = Seat(2), // lado 0 também
                hand = Guess(0, 44, correct = true),
                morto = Guess(0, 22, correct = true),
            ),
        )
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 200
        scores[1].dealGuessBonus shouldBe 0
    }

    @Test
    fun `individual o jogador que abre acumula os dois acertos no proprio lado`() {
        // §3.6 / §12.1 em individual, dealerSeat == mortoDealerSeat == firstSeat: acertar os dois dá 200 ao seu lado
        val state = finishedRound(
            GameMode.INDIVIDUAL,
            DealGuess(
                dealerSeat = Seat(0),
                mortoDealerSeat = Seat(0),
                hand = Guess(0, 22, correct = true),
                morto = Guess(0, 22, correct = true),
            ),
        )
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 200
        scores[1].dealGuessBonus shouldBe 0
        // sem mais nenhum bônus de mesa neste cenário (sem conjuntos, sem 3 vermelho, sem batida, mãos vazias)
        scores[0].total shouldBe 200
    }

    @Test
    fun `palpites errados nao rendem bonus nenhum`() {
        // §3.6 / §12.1 erro na largura: sem bônus
        val state = finishedRound(
            GameMode.DUPLAS,
            DealGuess(
                dealerSeat = Seat(3),
                mortoDealerSeat = Seat(2),
                hand = Guess(0, 10, correct = false),
                morto = Guess(0, 5, correct = false),
            ),
        )
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 0
        scores[1].dealGuessBonus shouldBe 0
    }

    @Test
    fun `sem dealGuess nenhum nao ha bonus`() {
        // §3.6 / §12.1 dealGuess null (parâmetro desligado, ou partida salva antiga): bônus sempre 0
        val state = round(GameMode.INDIVIDUAL) {
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            result = RoundResult.NoWinner
        }
        state.dealGuess shouldBe null
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 0
        scores[1].dealGuessBonus shouldBe 0
    }

    // ---------- §3.6 validação ----------

    @Test
    fun `acao fora da fase de palpite e recusada`() {
        // §3.6 GuessDealRange só é aceito nas fases DEAL_GUESS_HAND / DEAL_GUESS_MORTO
        val playing = round(GameMode.INDIVIDUAL) { hand(0, "5H 6H 7H KS") }
        playing.check(0, Action.GuessDealRange(0, 10)) shouldFailWith ActionError.NOT_GUESS_PHASE
    }

    @Test
    fun `qualquer outra acao durante a fase de palpite e recusada`() {
        // §3.6 o palpite pendente precisa ser resolvido antes de comprar, baixar ou descartar
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        RoundEngine.validate(state, state.currentSeat, Action.DrawFromStock) shouldFailWith ActionError.NOT_GUESS_PHASE
        RoundEngine.validate(state, state.currentSeat, Action.DeclineDraw) shouldFailWith ActionError.NOT_GUESS_PHASE
    }

    @Test
    fun `assento errado tentando palpitar e recusado`() {
        // §3.6 / §4.2 o gate de vez já existente cobre o palpite: só quem está em currentSeat pode agir
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val wrongSeat = state.mode.seats.first { it != state.currentSeat }
        RoundEngine.validate(state, wrongSeat, Action.GuessDealRange(0, 10)) shouldFailWith ActionError.NOT_YOUR_TURN
    }

    @Test
    fun `largura fora da faixa valida e recusada`() {
        // §3.6 0 <= start, end <= total de estágios (104 cartas sem coringas fora, §1)
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.currentSeat
        RoundEngine.validate(state, dealer, Action.GuessDealRange(-1, 10)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(state, dealer, Action.GuessDealRange(0, 105)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(state, dealer, Action.GuessDealRange(0, 104)).shouldBeOk() // 104 estágios: limite válido
    }

    // ---------- §3.6 limites do palpite do morto (22 a 60/82) ----------

    @Test
    fun `limite maximo do morto e 60 em duplas quando a mao palpitou largura minima`() {
        // §3.6 estágios do morto = baralho (104) − largura da mão, entre 22 e 60 (duplas)
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.currentSeat, Action.GuessDealRange(0, 0)) // largura 0
        val mortoDealer = afterHand.currentSeat
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 61)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 60)).shouldBeOk()
    }

    @Test
    fun `limite maximo do morto e 82 em individual quando a mao palpitou largura minima`() {
        // §3.6 estágios do morto em individual: 104 − largura da mão, entre 22 e 82
        val state = dealRound(GameMode.INDIVIDUAL, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.currentSeat, Action.GuessDealRange(0, 0))
        val mortoDealer = afterHand.currentSeat
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 83)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 82)).shouldBeOk()
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `limite minimo do morto e 22 quando a mao palpitou largura grande`(mode: GameMode) {
        // §3.6 largura da mão 90 deixa só 14 cartas (104-90), elevado ao mínimo de 22
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.currentSeat, Action.GuessDealRange(0, 90))
        val mortoDealer = afterHand.currentSeat
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 23)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(afterHand, mortoDealer, Action.GuessDealRange(0, 22)).shouldBeOk()
    }

    // ---------- §3.6 legalActions ----------

    @Test
    fun `legalActions na fase de palpite tem um candidato por largura possivel`() {
        // §3.6 um GuessDealRange por largura (0..104), já que o resultado só depende da largura
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        legal shouldHaveSize 105 // estágios 0..104
        legal.forEach { RoundEngine.validate(state, state.currentSeat, it).shouldBeOk() }
    }

    // ---------- §3.6 o palpite não altera a distribuição real ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `resolver os dois palpites produz exatamente a mesma distribuicao de com o parametro desligado`(mode: GameMode) {
        // §3.6 "o palpite não altera em nada a distribuição real": com a mesma semente, o estado após os dois
        // palpites (quaisquer larguras) é igual ao obtido com dealGuessBonusEnabled = false, exceto o próprio
        // campo dealGuess (que só existe com o parâmetro ligado)
        for (seed in 0L until 20L) {
            val withGuess = dealRound(mode, Random(seed))
            val withoutGuess = dealRound(mode, Random(seed), NO_DEAL_GUESS)
            val resolved = resolveDealGuesses(withGuess, handWidth = 1, mortoWidth = 1)
            resolved.copy(dealGuess = null) shouldBe withoutGuess
        }
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `dealGuessBonusEnabled desligado nao tem fase de palpite nenhuma`(mode: GameMode) {
        // §3.6 com o parâmetro desligado: a partida começa direto na 1ª vez, como antes da regra existir
        for (seed in 0L until 20L) {
            val state = dealRound(mode, Random(seed), NO_DEAL_GUESS)
            state.dealGuess shouldBe null
            state.phase shouldNotBe Phase.DEAL_GUESS_HAND
            state.phase shouldNotBe Phase.DEAL_GUESS_MORTO
            state.currentSeat shouldBe state.firstSeat
            state.turnsBegun shouldBe 1
        }
    }

    @Test
    fun `mesma semente com o parametro ligado gera a mesma fase de palpite`() {
        // reprodutibilidade: nenhuma aleatoriedade nova nesta fase (só ações explícitas)
        val a = dealRound(GameMode.DUPLAS, Random(42))
        val b = dealRound(GameMode.DUPLAS, Random(42))
        a shouldBe b
        val resolvedA = resolveDealGuesses(a, handWidth = 10, mortoWidth = 5)
        val resolvedB = resolveDealGuesses(b, handWidth = 10, mortoWidth = 5)
        resolvedA shouldBe resolvedB
    }

    /** Resolve os dois palpites pendentes de [state] com as larguras indicadas (início sempre 0), devolvendo o estado após. */
    private fun resolveDealGuesses(state: RoundState, handWidth: Int, mortoWidth: Int): RoundState {
        val afterHand = RoundEngine.apply(state, state.currentSeat, Action.GuessDealRange(0, handWidth))
        return RoundEngine.apply(afterHand, afterHand.currentSeat, Action.GuessDealRange(0, mortoWidth))
    }
}
