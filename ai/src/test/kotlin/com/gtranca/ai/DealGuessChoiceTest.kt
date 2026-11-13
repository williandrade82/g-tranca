package com.gtranca.ai

import com.gtranca.ai.hard.HardBot
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.RuleSet
import com.gtranca.engine.dealRound
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.engine.viewFor
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * §3.6 / §14.5 palpite da distribuição certeira pelos robôs, antes da 1ª vez: cada dificuldade acerta a
 * largura certa com a sua taxa (Fácil 5%, Médio 15%, Difícil 25%), e erra o resto do tempo com qualquer
 * outra largura.
 */
class DealGuessChoiceTest {

    // ---------- sempre devolve ação de `legal` ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `cada dificuldade escolhe sempre uma acao de legal no palpite da mao`(mode: GameMode) {
        for (seed in 0L until 50L) {
            val state = dealRound(mode, Random(seed))
            val legal = RoundEngine.legalActions(state, state.currentSeat)
            for (difficulty in Difficulty.entries) {
                val action = createBot(difficulty, Random(seed)).chooseAction(state.viewFor(state.currentSeat), legal)
                action.shouldBeInstanceOf<Action.GuessDealRange>()
                (action in legal) shouldBe true
            }
        }
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `cada dificuldade escolhe sempre uma acao de legal no palpite do morto`(mode: GameMode) {
        for (seed in 0L until 50L) {
            val afterHand = resolveHandGuess(dealRound(mode, Random(seed)))
            val legal = RoundEngine.legalActions(afterHand, afterHand.currentSeat)
            for (difficulty in Difficulty.entries) {
                val action = createBot(difficulty, Random(seed)).chooseAction(afterHand.viewFor(afterHand.currentSeat), legal)
                action.shouldBeInstanceOf<Action.GuessDealRange>()
                (action in legal) shouldBe true
            }
        }
    }

    // ---------- taxa de acerto por amostragem ----------

    @Test
    fun `facil acerta a largura da mao em cerca de 5 por cento das sementes`() {
        val rate = sampleHandGuessHitRate(Difficulty.FACIL, samples = 3000, baseSeed = 100L)
        rate shouldBe (EASY_DEAL_GUESS_ACCURACY plusOrMinus 0.02)
    }

    @Test
    fun `medio acerta a largura da mao em cerca de 15 por cento das sementes`() {
        val rate = sampleHandGuessHitRate(Difficulty.MEDIO, samples = 3000, baseSeed = 200L)
        rate shouldBe (MEDIUM_DEAL_GUESS_ACCURACY plusOrMinus 0.035)
    }

    @Test
    fun `dificil acerta a largura da mao em cerca de 25 por cento das sementes`() {
        // HardBotConfig sem teto de tempo: o palpite nunca passa pela busca mesmo assim (ver chooseAction),
        // mas usamos a config padrão do `:sim`/testes por consistência com os outros bots do Difícil.
        val rate = sampleHandGuessHitRate(Difficulty.DIFICIL, samples = 3000, baseSeed = 300L)
        rate shouldBe (HARD_DEAL_GUESS_ACCURACY plusOrMinus 0.04)
    }

    /** Fração de sementes (0 até [samples]-1, deslocadas por [baseSeed]) em que o bot acerta a largura da mão. */
    private fun sampleHandGuessHitRate(difficulty: Difficulty, samples: Int, baseSeed: Long): Double {
        var hits = 0
        for (i in 0 until samples) {
            val seed = baseSeed + i
            val state = dealRound(GameMode.DUPLAS, Random(seed))
            val legal = RoundEngine.legalActions(state, state.currentSeat)
            val action = createBot(difficulty, Random(seed)).chooseAction(state.viewFor(state.currentSeat), legal)
                as Action.GuessDealRange
            val target = dealGuessHandTarget(GameMode.DUPLAS, RuleSet.DEFAULT)
            if (action.end - action.start == target) hits++
        }
        return hits.toDouble() / samples
    }

    @Test
    fun `medio acerta a largura do morto em cerca de 15 por cento das sementes`() {
        var hits = 0
        val samples = 3000
        for (i in 0 until samples) {
            val seed = 400L + i
            val afterHand = resolveHandGuess(dealRound(GameMode.DUPLAS, Random(seed)))
            val legal = RoundEngine.legalActions(afterHand, afterHand.currentSeat)
            val action = createBot(Difficulty.MEDIO, Random(seed))
                .chooseAction(afterHand.viewFor(afterHand.currentSeat), legal) as Action.GuessDealRange
            val target = dealGuessMortoTarget(RuleSet.DEFAULT)
            if (action.end - action.start == target) hits++
        }
        (hits.toDouble() / samples) shouldBe (MEDIUM_DEAL_GUESS_ACCURACY plusOrMinus 0.035)
    }

    @Test
    fun `com taxa 0 nunca acerta e com taxa 1 sempre acerta`() {
        // §3.6 "quando não acertam, a largura escolhida é qualquer outra dentro da faixa disponível"
        val state = dealRound(GameMode.DUPLAS, Random(1))
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        val view = state.viewFor(state.currentSeat)
        val target = dealGuessHandTarget(GameMode.DUPLAS, RuleSet.DEFAULT)
        for (seed in 0L until 200L) {
            val missed = chooseDealGuess(view, legal, Random(seed), accuracy = 0.0) as Action.GuessDealRange
            (missed.end - missed.start != target) shouldBe true
            val hit = chooseDealGuess(view, legal, Random(seed), accuracy = 1.0) as Action.GuessDealRange
            (hit.end - hit.start == target) shouldBe true
        }
    }

    // ---------- reprodutibilidade ----------

    @ParameterizedTest
    @EnumSource(Difficulty::class)
    fun `mesma semente produz a mesma escolha de palpite`(difficulty: Difficulty) {
        val state = dealRound(GameMode.DUPLAS, Random(42))
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        val view = state.viewFor(state.currentSeat)
        val first = createBot(difficulty, Random(7)).chooseAction(view, legal)
        val second = createBot(difficulty, Random(7)).chooseAction(view, legal)
        first shouldBe second
    }

    // ---------- Difícil: o palpite não gasta o orçamento de busca ----------

    @Test
    fun `hardbot resolve o palpite sem rodar o ISMCTS`() {
        val state = dealRound(GameMode.DUPLAS, Random(55))
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        val bot = HardBot(Random(9))
        val action = bot.chooseAction(state.viewFor(state.currentSeat), legal)
        action.shouldBeInstanceOf<Action.GuessDealRange>()
        bot.lastIterations shouldBe 0
        bot.timeLimitHits shouldBe 0
    }

    // ---------- regressão: fases normais continuam exatamente como antes ----------

    @Test
    fun `com o parametro desligado a partida nunca entra nas fases de palpite`() {
        val rules = RuleSet.DEFAULT.copy(dealGuessBonusEnabled = false)
        for (mode in GameMode.entries) {
            for (seed in 0L until 20L) {
                val state = dealRound(mode, Random(seed), rules)
                state.phase shouldBe Phase.AWAITING_DRAW
                state.dealGuess shouldBe null
                // as mesmas ações de sempre: nenhum bot precisa do ramo novo para decidir esta fase
                val legal = RoundEngine.legalActions(state, state.currentSeat)
                val action = createBot(Difficulty.MEDIO, Random(seed)).chooseAction(state.viewFor(state.currentSeat), legal)
                (action in legal) shouldBe true
                (action is Action.GuessDealRange) shouldBe false // lixo vazio na 1ª vez: nunca o ramo novo
            }
        }
    }

    /** Resolve o palpite da mão com uma largura qualquer (a primeira ação legal), avançando para a fase do morto. */
    private fun resolveHandGuess(state: RoundState): RoundState {
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        return RoundEngine.apply(state, state.currentSeat, legal.first())
    }
}
