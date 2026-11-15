package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.DEAL_GUESS_FIXED_START
import com.gtranca.engine.model.DealGuess
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Guess
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.dealGuessAction
import com.gtranca.engine.model.dealGuessHandRange
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoRange
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.engine.model.dealGuessSeats
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
 *
 * Ajuste de 14/11/2026 (toque único): a faixa de números oferecida encolheu e passou a ser fixa por papel/modo
 * (35..55 duplas / 12..32 individual na mão; 12..32 em qualquer modo no morto), não mais derivada do baralho
 * restante. O formato da [Action.GuessDealRange] continua o mesmo (para não exigir bump de `:net`), mas agora
 * só é aceito com `start` fixo em 1 ([DEAL_GUESS_FIXED_START]); o número escolhido é `end - start`. Ver
 * [dealGuessAction] (o único jeito suportado de montar essa ação).
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
    fun `individual o outro assento distribui a mao e quem abre a partida distribui o morto`() {
        // §3.6 em individual (2 assentos), "o anterior" a quem abre a partida é o outro jogador (palpita a mão);
        // "o anterior a esse" volta a quem abre a partida (palpita o morto e, a seguir, começa a jogar de fato)
        val state = dealRound(GameMode.INDIVIDUAL, Random(1), firstSeat = Seat(0))
        state.phase shouldBe Phase.DEAL_GUESS_HAND
        state.currentSeat shouldBe Seat(1)
        state.dealGuess shouldBe DealGuess(dealerSeat = Seat(1), mortoDealerSeat = Seat(0))

        val afterHand = RoundEngine.apply(state, Seat(1), dealGuessAction(22)) // 22 = alvo individual
        afterHand.phase shouldBe Phase.DEAL_GUESS_MORTO
        afterHand.currentSeat shouldBe Seat(0) // volta a quem abre a partida, assento diferente de quem deu a mão
        afterHand.dealGuess?.hand shouldBe Guess(DEAL_GUESS_FIXED_START, DEAL_GUESS_FIXED_START + 22, correct = true)
    }

    @Test
    fun `individual os dois papeis trocam de assento quando quem abre a partida e o assento 1`() {
        // §3.6 simetria: com firstSeat = 1, o assento 0 distribui a mão e o 1 (quem abre) distribui o morto
        val state = dealRound(GameMode.INDIVIDUAL, Random(1), firstSeat = Seat(1))
        state.dealGuess shouldBe DealGuess(dealerSeat = Seat(0), mortoDealerSeat = Seat(1))
    }

    @Test
    fun `quem distribui a mao e quem distribui o morto ficam sempre em lados diferentes`() {
        // §12.1 os dois bônus de +100 (mão e morto) nunca vão para o mesmo lado: dealerSeat e mortoDealerSeat
        // (§3.6) são sempre de lados opostos, em qualquer modo e qualquer firstSeat (não só nos casos pontuais
        // já cobertos acima) — invariante estrutural de dealGuessSeats, não um acaso das sementes usadas alhures.
        GameMode.entries.forEach { mode ->
            mode.seats.forEach { firstSeat ->
                val (dealerSeat, mortoDealerSeat) = dealGuessSeats(mode, firstSeat)
                mode.sideOf(dealerSeat) shouldNotBe mode.sideOf(mortoDealerSeat)
            }
        }
    }

    // ---------- §3.6 faixas fixas (ajuste de 14/11/2026) ----------

    @Test
    fun `faixa da mao e 35 a 55 em duplas e 12 a 32 em individual`() {
        // §3.6 faixa encolhida e fixa, não mais derivada do baralho
        dealGuessHandRange(GameMode.DUPLAS) shouldBe 35..55
        dealGuessHandRange(GameMode.INDIVIDUAL) shouldBe 12..32
    }

    @Test
    fun `faixa do morto e 12 a 32 em qualquer modo`() {
        // §3.6 faixa do morto fixa, independente do modo e da largura escolhida na mão
        dealGuessMortoRange() shouldBe 12..32
    }

    // ---------- §3.6 acerto e erro do palpite da mão ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `palpite da mao acerta com o numero igual a cardsPerHand vezes o numero de assentos`(mode: GameMode) {
        // §3.6 alvo da mão: 44 em duplas, 22 em individual (11 cartas por jogador, §3.1); dentro da faixa nova
        val target = RuleSet.DEFAULT.cardsPerHand * mode.seatCount
        target shouldBeInRangeOf dealGuessHandRange(mode)
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, dealGuessAction(target))
        resolved.dealGuess?.hand shouldBe Guess(DEAL_GUESS_FIXED_START, DEAL_GUESS_FIXED_START + target, correct = true)
        resolved.phase shouldBe Phase.DEAL_GUESS_MORTO
    }

    @Test
    fun `palpite da mao com numero errado nao acerta mas resolve normalmente`() {
        // §3.6 número diferente do alvo, mas dentro da faixa: palpite registrado, sem acerto, segue para o morto
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, dealGuessAction(35)) // 35 != 44 (alvo duplas)
        resolved.dealGuess?.hand shouldBe Guess(DEAL_GUESS_FIXED_START, DEAL_GUESS_FIXED_START + 35, correct = false)
        resolved.phase shouldBe Phase.DEAL_GUESS_MORTO
        resolved.currentSeat shouldBe state.dealGuess.mortoDealerSeat
    }

    @Test
    fun `start diferente de 1 e recusado mesmo com largura dentro da faixa antiga`() {
        // §3.6 (14/11/2026) toque único: só o formato start=1 é aceito; a "normalização de start/end trocados"
        // do toque duplo deixou de existir — um par que seria válido no baralho inteiro de antes é recusado agora
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        RoundEngine.validate(state, dealer, Action.GuessDealRange(0, 44)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(state, dealer, Action.GuessDealRange(16, 60)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(state, dealer, Action.GuessDealRange(60, 16)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
    }

    // ---------- §3.6 acerto e erro do palpite do morto ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `palpite do morto acerta com numero 22`(mode: GameMode) {
        // §3.6 alvo do morto: 22 (11 cartas por morto x 2 mortos, §3.2), em qualquer modo
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.dealGuess!!.dealerSeat, dealGuessAction(dealGuessHandRange(mode).first))
        val mortoDealer = afterHand.dealGuess!!.mortoDealerSeat
        val resolved = RoundEngine.apply(afterHand, mortoDealer, dealGuessAction(22))
        resolved.dealGuess?.morto shouldBe Guess(DEAL_GUESS_FIXED_START, DEAL_GUESS_FIXED_START + 22, correct = true)
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
                hand = Guess(1, 45, correct = true),
                morto = Guess(1, 23, correct = true),
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
                hand = Guess(1, 45, correct = true),
                morto = Guess(1, 23, correct = true),
            ),
        )
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 200
        scores[1].dealGuessBonus shouldBe 0
    }

    @Test
    fun `individual os dois acertos vao para lados diferentes um para cada assento`() {
        // §3.6 / §12.1 em individual, dealerSeat e mortoDealerSeat são sempre assentos diferentes: cada acerto
        // rende 100 ao lado de quem palpitou, um para cada um dos dois lados (cada assento é o seu próprio lado)
        val state = finishedRound(
            GameMode.INDIVIDUAL,
            DealGuess(
                dealerSeat = Seat(1),
                mortoDealerSeat = Seat(0),
                hand = Guess(1, 23, correct = true),
                morto = Guess(1, 23, correct = true),
            ),
        )
        val scores = scoreRound(state)
        scores[0].dealGuessBonus shouldBe 100 // lado 0: acertou o morto
        scores[1].dealGuessBonus shouldBe 100 // lado 1: acertou a mão
        // sem mais nenhum bônus de mesa neste cenário (sem conjuntos, sem 3 vermelho, sem batida, mãos vazias)
        scores[0].total shouldBe 100
        scores[1].total shouldBe 100
    }

    @Test
    fun `palpites errados nao rendem bonus nenhum`() {
        // §3.6 / §12.1 número errado: sem bônus
        val state = finishedRound(
            GameMode.DUPLAS,
            DealGuess(
                dealerSeat = Seat(3),
                mortoDealerSeat = Seat(2),
                hand = Guess(1, 36, correct = false),
                morto = Guess(1, 13, correct = false),
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
        playing.check(0, dealGuessAction(22)) shouldFailWith ActionError.NOT_GUESS_PHASE
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
        RoundEngine.validate(state, wrongSeat, dealGuessAction(44)) shouldFailWith ActionError.NOT_YOUR_TURN
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `bordas da faixa da mao sao aceitas e um numero fora e recusado`(mode: GameMode) {
        // §3.6 bordas inclusivas da faixa nova (35/55 duplas, 12/32 individual)
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val dealer = state.currentSeat
        val range = dealGuessHandRange(mode)
        RoundEngine.validate(state, dealer, dealGuessAction(range.first)).shouldBeOk()
        RoundEngine.validate(state, dealer, dealGuessAction(range.last)).shouldBeOk()
        RoundEngine.validate(state, dealer, dealGuessAction(range.first - 1)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(state, dealer, dealGuessAction(range.last + 1)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
    }

    @Test
    fun `um numero que era valido no baralho inteiro de antes agora e recusado`() {
        // §3.6 (14/11/2026) 90 cabia no baralho inteiro (0..104) de antes, mas está fora das faixas novas
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val dealer = state.currentSeat
        RoundEngine.validate(state, dealer, Action.GuessDealRange(1, 1 + 90)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
    }

    @Test
    fun `bordas da faixa do morto sao aceitas e um numero fora e recusado`() {
        // §3.6 bordas inclusivas da faixa nova do morto (12/32), fixa em qualquer modo
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.currentSeat, dealGuessAction(44))
        val mortoDealer = afterHand.currentSeat
        val range = dealGuessMortoRange()
        RoundEngine.validate(afterHand, mortoDealer, dealGuessAction(range.first)).shouldBeOk()
        RoundEngine.validate(afterHand, mortoDealer, dealGuessAction(range.last)).shouldBeOk()
        RoundEngine.validate(afterHand, mortoDealer, dealGuessAction(range.first - 1)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
        RoundEngine.validate(afterHand, mortoDealer, dealGuessAction(range.last + 1)) shouldFailWith ActionError.GUESS_RANGE_OUT_OF_BOUNDS
    }

    @Test
    fun `faixa do morto nao depende mais da largura escolhida na mao`() {
        // §3.6 (14/11/2026) a faixa do morto é sempre 12..32, mesmo quando a mão escolheu o extremo da sua faixa
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val afterMin = RoundEngine.apply(state, state.currentSeat, dealGuessAction(dealGuessHandRange(GameMode.DUPLAS).first))
        val afterMax = RoundEngine.apply(state, state.currentSeat, dealGuessAction(dealGuessHandRange(GameMode.DUPLAS).last))
        RoundEngine.legalActions(afterMin, afterMin.currentSeat) shouldBe RoundEngine.legalActions(afterMax, afterMax.currentSeat)
    }

    // ---------- §3.6 dealGuessHandTarget / dealGuessMortoTarget (helpers usados por :ai e, depois, pela UI) ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `dealGuessHandTarget acerta o mesmo numero que o motor usa para a mao`(mode: GameMode) {
        // §3.6 confere que o helper bate com a fórmula inline de RoundEngine.guessDealRange
        val target = dealGuessHandTarget(mode, RuleSet.DEFAULT)
        val state = dealRound(mode, Random(9), firstSeat = Seat(0))
        val dealer = state.dealGuess!!.dealerSeat
        val resolved = RoundEngine.apply(state, dealer, dealGuessAction(target))
        resolved.dealGuess?.hand?.correct shouldBe true
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `dealGuessMortoTarget acerta o mesmo numero que o motor usa para o morto`(mode: GameMode) {
        // §3.6 alvo do morto é fixo (22), independente do modo ou do número escolhido na mão
        val target = dealGuessMortoTarget(RuleSet.DEFAULT)
        val state = dealRound(mode, Random(9), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.dealGuess!!.dealerSeat, dealGuessAction(dealGuessHandRange(mode).first))
        val mortoDealer = afterHand.dealGuess!!.mortoDealerSeat
        val resolved = RoundEngine.apply(afterHand, mortoDealer, dealGuessAction(target))
        resolved.dealGuess?.morto?.correct shouldBe true
    }

    // ---------- §3.6 legalActions ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `legalActions na fase de palpite da mao tem um candidato por numero possivel na faixa fixa`(mode: GameMode) {
        // §3.6 (14/11/2026) um dealGuessAction por número da faixa fixa do modo (21 números: 35..55 ou 12..32)
        val state = dealRound(mode, Random(5), firstSeat = Seat(0))
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        legal shouldHaveSize dealGuessHandRange(mode).count()
        legal.forEach { RoundEngine.validate(state, state.currentSeat, it).shouldBeOk() }
    }

    @Test
    fun `legalActions na fase de palpite do morto tem um candidato por numero possivel na faixa fixa`() {
        // §3.6 (14/11/2026) faixa do morto fixa (12..32 = 21 números), em qualquer modo
        val state = dealRound(GameMode.DUPLAS, Random(5), firstSeat = Seat(0))
        val afterHand = RoundEngine.apply(state, state.currentSeat, dealGuessAction(44))
        val legal = RoundEngine.legalActions(afterHand, afterHand.currentSeat)
        legal shouldHaveSize dealGuessMortoRange().count()
        legal.forEach { RoundEngine.validate(afterHand, afterHand.currentSeat, it).shouldBeOk() }
    }

    // ---------- §3.6 o palpite não altera a distribuição real ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `resolver os dois palpites produz exatamente a mesma distribuicao de com o parametro desligado`(mode: GameMode) {
        // §3.6 "o palpite não altera em nada a distribuição real": com a mesma semente, o estado após os dois
        // palpites (quaisquer números válidos) é igual ao obtido com dealGuessBonusEnabled = false, exceto o
        // próprio campo dealGuess (que só existe com o parâmetro ligado)
        for (seed in 0L until 20L) {
            val withGuess = dealRound(mode, Random(seed))
            val withoutGuess = dealRound(mode, Random(seed), NO_DEAL_GUESS)
            val resolved = resolveDealGuesses(withGuess, mode)
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
        val resolvedA = resolveDealGuesses(a, GameMode.DUPLAS)
        val resolvedB = resolveDealGuesses(b, GameMode.DUPLAS)
        resolvedA shouldBe resolvedB
    }

    /** Resolve os dois palpites pendentes de [state], com o menor número válido da faixa fixa de cada fase. */
    private fun resolveDealGuesses(state: RoundState, mode: GameMode): RoundState {
        val afterHand = RoundEngine.apply(state, state.currentSeat, dealGuessAction(dealGuessHandRange(mode).first))
        return RoundEngine.apply(afterHand, afterHand.currentSeat, dealGuessAction(dealGuessMortoRange().first))
    }

    private infix fun Int.shouldBeInRangeOf(range: IntRange) {
        (this in range) shouldBe true
    }
}
