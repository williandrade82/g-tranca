package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.botSeed
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.floats.shouldBeBetween
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Dramatização da batida (docs/telas.md 3.9): voo lento com a carta que cresce, fogos e só então o banner. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoOutDramaTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a carta cresce em 30 por cento, fica grande por 45 e volta ao normal ao pousar`() {
        GoOutDrama.flightScale(0f) shouldBe (1f plusOrMinus 1e-6f)
        GoOutDrama.flightScale(0.3f) shouldBe (GoOutDrama.PEAK_SCALE plusOrMinus 1e-6f)
        GoOutDrama.flightScale(0.5f) shouldBe (GoOutDrama.PEAK_SCALE plusOrMinus 1e-6f)
        GoOutDrama.flightScale(0.75f) shouldBe (GoOutDrama.PEAK_SCALE plusOrMinus 1e-6f)
        GoOutDrama.flightScale(1f) shouldBe (1f plusOrMinus 1e-6f)
        // Crescimento e volta graduais (monotônicos).
        val up = (0..10).map { GoOutDrama.flightScale(it * 0.04f) }
        up.zipWithNext().all { (a, b) -> b >= a - 1e-6f } shouldBe true
        val down = (0..10).map { GoOutDrama.flightScale(0.75f + it * 0.025f) }
        down.zipWithNext().all { (a, b) -> b <= a + 1e-6f } shouldBe true
        GoOutDrama.PEAK_SCALE.shouldBeBetween(2.0f, 3.0f, 0f)
    }

    @Test
    fun `a sequencia e fogos e depois banner, sem fogos sem animacao nem sem batida`() {
        GoOutDrama.startPhase(RoundEnd.YOU, animate = true) shouldBe GoOutDrama.OverlayPhase.FIREWORKS
        GoOutDrama.startPhase(RoundEnd.PARTNER, animate = true) shouldBe GoOutDrama.OverlayPhase.FIREWORKS
        GoOutDrama.startPhase(RoundEnd.OPPONENT, animate = true) shouldBe GoOutDrama.OverlayPhase.FIREWORKS
        GoOutDrama.next(GoOutDrama.OverlayPhase.FIREWORKS) shouldBe GoOutDrama.OverlayPhase.BANNER
        GoOutDrama.startPhase(RoundEnd.YOU, animate = false) shouldBe GoOutDrama.OverlayPhase.BANNER
        GoOutDrama.startPhase(RoundEnd.NO_WINNER, animate = true) shouldBe GoOutDrama.OverlayPhase.BANNER
        GoOutDrama.FIREWORKS_MILLIS shouldBe 3_000L
    }

    @Test
    fun `o tremor da mesa comeca e termina parado e fica amortecido`() {
        GoOutDrama.shake(0f) shouldBe Triple(0f, 0f, 0f)
        GoOutDrama.shake(1f) shouldBe Triple(0f, 0f, 0f)
        val early = (1..9).maxOf { kotlin.math.abs(GoOutDrama.shake(it * 0.02f).first) }
        val late = (1..9).maxOf { kotlin.math.abs(GoOutDrama.shake(0.8f + it * 0.02f).first) }
        (early > late) shouldBe true
        GoOutDrama.SHAKE_MILLIS shouldBe 450
    }

    @Test
    fun `fogos verdes na vitoria (voce ou o parceiro) e vermelhos na derrota`() {
        GoOutDrama.fireworksWin(RoundEnd.YOU) shouldBe true
        GoOutDrama.fireworksWin(RoundEnd.PARTNER) shouldBe true
        GoOutDrama.fireworksWin(RoundEnd.OPPONENT) shouldBe false
    }

    @Test
    fun `as faiscas nascem, ficam na tela e apagam dentro da duracao dos fogos`() {
        val bursts = Fireworks.create(seed = 5)
        bursts.size shouldBe Fireworks.BURSTS
        // Nenhum estouro passa do fim e todos têm faíscas visíveis em algum momento.
        bursts.forEach { burst ->
            (burst.start + Fireworks.BURST_LIFE <= 1.0001f) shouldBe true
            val mid = burst.start + Fireworks.BURST_LIFE / 2
            val visible = burst.sparks.mapNotNull { Fireworks.state(burst, it, mid) }
            visible.shouldNotBeEmpty()
            visible.forEach { (it.alpha > 0f) shouldBe true }
            burst.sparks.all { Fireworks.state(burst, it, (burst.start - 0.01f).coerceAtLeast(-1f)) == null } shouldBe true
            burst.sparks.all { Fireworks.state(burst, it, burst.start + Fireworks.BURST_LIFE + 0.001f) == null } shouldBe true
        }
        // Mesma semente, mesmos fogos.
        Fireworks.create(5) shouldBe bursts
    }

    @Test
    fun `§11_1 so os voos da mao de quem bateu viram a batida lenta, em qualquer assento`() = runTest(dispatcher) {
        val mode = GameMode.DUPLAS
        val seed = 21L
        val controller = GameController(
            GameConfig(mode, Difficulty.MEDIO, targetScore = 1000), seed,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(seed, it.index)))) },
            computeDispatcher = dispatcher, botDelayMillis = 1,
        )
        val snapshots = mutableListOf<GameSnapshot>()
        val collector = launch { controller.state.collect { snapshots += it } }
        controller.run()
        collector.cancel()
        var id = 0L
        var finales = 0
        snapshots.zipWithNext().forEach { (old, new) ->
            if (new.view.phase != Phase.FINISHED || old.view.phase == Phase.FINISHED) return@forEach
            val winner = (new.view.result as? RoundResult.GoOut)?.seat ?: return@forEach
            val normal = TableAnimations.derive(old, new, emptySet()) { ++id }.first
            val dramatic = TableAnimations.derive(old, new, emptySet(), goOut = true) { ++id }.first
            normal.none { it.finale } shouldBe true
            dramatic.size shouldBe normal.size
            val slow = dramatic.filter { it.finale }
            slow.shouldNotBeEmpty()
            slow.forEach {
                it.durationFactor shouldBe GoOutDrama.SLOWDOWN
                // Só saem da mão de quem bateu (viewer 0: OwnHand; os outros: a mão representada).
                val hand = if (winner == new.viewerSeat) AnimAnchor.OwnHand else AnimAnchor.SeatHand(winner.index)
                it.from shouldBe hand
            }
            finales += slow.size
        }
        (finales > 0) shouldBe true
    }
}
