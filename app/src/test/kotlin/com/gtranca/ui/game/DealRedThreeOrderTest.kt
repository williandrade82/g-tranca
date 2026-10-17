package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §3.5 ordem do início com 3 vermelho de um bot que joga primeiro: distribuição, sinal da tela de que ela terminou,
 * aviso, voo da troca, jogada do bot e só então a vez do humano. Nada da troca aparece durante a distribuição, mesmo que a
 * tela demore a avisar que terminou.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealRedThreeOrderTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private companion object {
        const val STEP = 50L
        const val ANIMATION = 450L
        const val SWAP_PAUSE_MAX = 400L
    }

    @Test
    fun `§3_5 3 vermelho do bot que abre o jogo so aparece depois de a distribuicao terminar, na ordem certa`() = runTest(dispatcher) {
        val seatsCovered = mutableSetOf<Int>()
        var runs = 0
        for (mode in GameMode.entries) {
            for (slow in listOf(false, true)) {
                for (screenLateBy in listOf(0L, 800L)) {
                    for (seed in 1L..40L) {
                        val vm = GameViewModel(
                            GameConfig(mode, Difficulty.FACIL, 3000),
                            gameSeed = seed,
                            computeDispatcher = dispatcher,
                            botDelayMillis = 700,
                            swapAnimationMillis = 1_800,
                            animationMillis = ANIMATION,
                            slowAnimations = slow,
                        )
                        val initial = vm.uiState.value
                        val firstSwap = initial.snapshot.view.redThreeLog.firstOrNull() ?: continue
                        val botSeat = firstSwap.seat
                        if (botSeat == Seat(0)) continue // o humano abre: cenário seguinte
                        runs++
                        seatsCovered += botSeat.index
                        val loggedCards = initial.snapshot.view.redThreeLog.filter { it.seat == botSeat }.map { it.card }

                        // 1. t = 0: voos de distribuição e a troca na fila (ainda sem aviso).
                        initial.flights.shouldNotBeEmpty()
                        val dealIds = initial.flights.map { it.id }.toSet()
                        val scale = GameViewModel.speedFor(slow)
                        val dealEnd = initial.flights.maxOf { it.delayMillis } + (ANIMATION * scale).toLong()
                        val signalAt = dealEnd + screenLateBy

                        var t = 0L
                        var signalTime: Long? = null
                        var bannerAt: Long? = null
                        var swapFlightAt: Long? = null
                        var botPlayedAt: Long? = null
                        var humanAt: Long? = null
                        withClue("modo $mode, bot $botSeat, semente $seed, lenta=$slow, tela atrasa $screenLateBy ms") {
                            while (t < 30_000 && humanAt == null) {
                                val state = vm.uiState.value
                                if (signalTime == null && t >= signalAt) {
                                    vm.onDealAnimationFinished()
                                    signalTime = t
                                }
                                val sent = signalTime != null
                                val newFlights = state.flights.filter { it.id !in dealIds }
                                val swapFlights = newFlights.filter { it.to is AnimAnchor.RedThrees }
                                val botPlayed = state.snapshot.turnEvents.getOrNull(botSeat.index).orEmpty().isNotEmpty()
                                if (!sent) {
                                    // 2. Durante a distribuição (e até a tela avisar): nada da troca nem do bot.
                                    (state.banner == null) shouldBe true
                                    swapFlights.isEmpty() shouldBe true
                                    newFlights.isEmpty() shouldBe true
                                    botPlayed shouldBe false
                                    state.isHumanTurn shouldBe false
                                } else {
                                    if (bannerAt == null && state.banner != null) bannerAt = t
                                    if (swapFlightAt == null && swapFlights.isNotEmpty()) swapFlightAt = t
                                    if (botPlayedAt == null && botPlayed) botPlayedAt = t
                                }
                                if (state.isHumanTurn || state.reveal != null) humanAt = t
                                advanceTimeBy(STEP)
                                runCurrent()
                                t += STEP
                            }
                            // 3. a tela avisou; 4. aviso, voo da troca, jogada do bot, vez do humano.
                            val sentAt = signalTime.orFail()
                            val banner = bannerAt.orFail()
                            val flight = swapFlightAt.orFail()
                            val played = botPlayedAt.orFail()
                            val human = humanAt.orFail()
                            (banner >= sentAt) shouldBe true
                            (flight >= banner) shouldBe true
                            // 5. pausa curta entre o sinal e o voo da troca (sem 1,8 s de aviso parado).
                            withClue("voo da troca ${flight - sentAt} ms depois do sinal") { (flight - sentAt <= SWAP_PAUSE_MAX + STEP) shouldBe true }
                            // 6. o bot só joga depois de a troca pousar.
                            withClue("bot jogou em $played, voo em $flight") { (played >= flight + (ANIMATION * scale).toLong() - STEP) shouldBe true }
                            // 7. vez do humano: sem aviso e com o 3 vermelho do bot na mesa.
                            (human >= played) shouldBe true
                            vm.uiState.value.banner shouldBe null
                            vm.uiState.value.redThrees[vm.uiState.value.snapshot.view.mode.sideOf(botSeat).index] shouldContainAll loggedCards
                        }
                    }
                }
            }
        }
        withClue("jogos com 3 vermelho no início de um bot: $runs, assentos: $seatsCovered") {
            (runs >= 20) shouldBe true
            (seatsCovered.size >= 2) shouldBe true
        }
    }

    private fun Long?.orFail(): Long = this ?: error("passo do fluxo não aconteceu")
}
