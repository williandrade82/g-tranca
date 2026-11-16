package com.gtranca.ai

import com.gtranca.ai.hard.HardBot
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.RuleSet
import com.gtranca.engine.dealRound
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.DEAL_GUESS_FIXED_START
import com.gtranca.engine.model.dealGuessHandRange
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoRange
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.engine.viewFor
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.random.Random

/**
 * §3.6 / §14.5 palpite da distribuição certeira pelos robôs, antes da 1ª vez: cada dificuldade acerta o
 * **número** certo (toque único, faixa fixa por papel/modo — ajuste de 14/11/2026) com a sua taxa (Fácil
 * 5%, Médio 15%, Difícil 25%), e erra o resto do tempo com qualquer outro número da mesma faixa.
 */
class DealGuessChoiceTest {

    /** §3.6 qual dos dois palpites (feitos em sequência, ver §3.6) está sendo exercitado num teste. */
    enum class GuessRole { HAND, MORTO }

    companion object {
        /** Combinações de modo × papel (mão/morto): 2 × 2 = 4. */
        @JvmStatic
        fun modeRoleCombos(): Stream<Arguments> =
            GameMode.entries.flatMap { mode -> GuessRole.entries.map { role -> Arguments.of(mode, role) } }.stream()

        /** Combinações de dificuldade × modo × papel, cada uma com sua taxa/tolerância/semente-base: 3×2×2=12. */
        @JvmStatic
        fun rateCombos(): Stream<Arguments> {
            val combos = mutableListOf<Arguments>()
            var seedOffset = 1_000L
            for (difficulty in Difficulty.entries) {
                val (accuracy, tolerance) = when (difficulty) {
                    Difficulty.FACIL -> EASY_DEAL_GUESS_ACCURACY to 0.02
                    Difficulty.MEDIO -> MEDIUM_DEAL_GUESS_ACCURACY to 0.035
                    Difficulty.DIFICIL -> HARD_DEAL_GUESS_ACCURACY to 0.04
                }
                for (mode in GameMode.entries) {
                    for (role in GuessRole.entries) {
                        combos += Arguments.of(difficulty, mode, role, accuracy, tolerance, seedOffset)
                        seedOffset += 10_000L
                    }
                }
            }
            return combos.stream()
        }
    }

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

    // ---------- taxa de acerto por amostragem (dificuldade × modo × papel) ----------

    @ParameterizedTest
    @MethodSource("rateCombos")
    fun `cada dificuldade acerta o numero certo na taxa do seu nivel, em cada modo e papel`(
        difficulty: Difficulty,
        mode: GameMode,
        role: GuessRole,
        accuracy: Double,
        tolerance: Double,
        baseSeed: Long,
    ) {
        val rate = sampleGuessHitRate(difficulty, mode, role, samples = 3000, baseSeed = baseSeed)
        rate shouldBe (accuracy plusOrMinus tolerance)
    }

    /** Fração de sementes (deslocadas por [baseSeed]) em que o bot acerta o número certo de [role] em [mode]. */
    private fun sampleGuessHitRate(difficulty: Difficulty, mode: GameMode, role: GuessRole, samples: Int, baseSeed: Long): Double {
        var hits = 0
        val target = targetFor(mode, role)
        for (i in 0 until samples) {
            val seed = baseSeed + i
            val state = stateBeforeGuess(mode, role, seed)
            val legal = RoundEngine.legalActions(state, state.currentSeat)
            val action = createBot(difficulty, Random(seed)).chooseAction(state.viewFor(state.currentSeat), legal)
                as Action.GuessDealRange
            if (action.end - action.start == target) hits++
        }
        return hits.toDouble() / samples
    }

    // ---------- quando acerta/erra: número dentro da faixa nova, e o motor aceita e resolve certo ----------

    @ParameterizedTest
    @MethodSource("modeRoleCombos")
    fun `quando acerta, o numero bate com o alvo e o motor marca correct = true`(mode: GameMode, role: GuessRole) {
        val target = targetFor(mode, role)
        val range = rangeFor(mode, role)
        for (seed in 0L until 200L) {
            val state = stateBeforeGuess(mode, role, seed)
            val legal = RoundEngine.legalActions(state, state.currentSeat)
            // §3.6/LegalActions: um candidato por número da faixa fixa (21 números), todos com start=1 (toque único).
            legal.size shouldBe range.count()
            legal.all { (it as Action.GuessDealRange).start == DEAL_GUESS_FIXED_START } shouldBe true
            val view = state.viewFor(state.currentSeat)
            val hit = chooseDealGuess(view, legal, Random(seed), accuracy = 1.0) as Action.GuessDealRange
            (hit.end - hit.start) shouldBe target

            val resolved = RoundEngine.apply(state, state.currentSeat, hit)
            val guess = resolvedGuess(resolved, role)
            guess.correct shouldBe true
            guess.width shouldBe target
        }
    }

    @ParameterizedTest
    @MethodSource("modeRoleCombos")
    fun `quando erra, o numero esta na faixa fixa nova, nunca eh o alvo, e o motor aceita marcando correct = false`(
        mode: GameMode,
        role: GuessRole,
    ) {
        val range = rangeFor(mode, role)
        val target = targetFor(mode, role)
        val missedNumbers = mutableSetOf<Int>()
        for (seed in 0L until 300L) {
            val state = stateBeforeGuess(mode, role, seed)
            val legal = RoundEngine.legalActions(state, state.currentSeat)
            val view = state.viewFor(state.currentSeat)
            val missed = chooseDealGuess(view, legal, Random(seed), accuracy = 0.0) as Action.GuessDealRange
            val number = missed.end - missed.start
            (number in range) shouldBe true
            number shouldNotBe target
            missedNumbers += number

            val resolved = RoundEngine.apply(state, state.currentSeat, missed)
            val guess = resolvedGuess(resolved, role)
            guess.correct shouldBe false
            guess.width shouldBe number
        }
        // §3.6 "qualquer outro dentro da faixa": com 300 sementes, as 20 larguras restantes aparecem todas.
        missedNumbers shouldBe (range.toSet() - target)
    }

    private fun resolvedGuess(state: RoundState, role: GuessRole): com.gtranca.engine.model.Guess = when (role) {
        GuessRole.HAND -> state.dealGuess!!.hand!!
        GuessRole.MORTO -> state.dealGuess!!.morto!!
    }

    private fun targetFor(mode: GameMode, role: GuessRole): Int = when (role) {
        GuessRole.HAND -> dealGuessHandTarget(mode, RuleSet.DEFAULT)
        GuessRole.MORTO -> dealGuessMortoTarget(RuleSet.DEFAULT)
    }

    private fun rangeFor(mode: GameMode, role: GuessRole): IntRange = when (role) {
        GuessRole.HAND -> dealGuessHandRange(mode)
        GuessRole.MORTO -> dealGuessMortoRange()
    }

    /** Estado da partida no exato momento em que [role] vai palpitar, para [mode] e [seed]. */
    private fun stateBeforeGuess(mode: GameMode, role: GuessRole, seed: Long): RoundState {
        val dealt = dealRound(mode, Random(seed))
        return when (role) {
            GuessRole.HAND -> dealt
            GuessRole.MORTO -> resolveHandGuess(dealt)
        }
    }

    @Test
    fun `com taxa 0 nunca acerta e com taxa 1 sempre acerta`() {
        // §3.6 (ajuste de 14/11/2026) "quando não acertam, o número escolhido é qualquer outro dentro da
        // faixa (35-55 ou 12-32, conforme o papel e o modo)"
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
