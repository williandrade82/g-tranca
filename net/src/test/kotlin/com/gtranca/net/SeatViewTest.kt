package com.gtranca.net

import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.DealGuess
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Guess
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * §3.6, §16.4: [SeatView] carrega o mesmo [PlayerView.dealGuess] que um robô (e o anfitrião) já viam — antes desta
 * correção o campo não existia em [SeatView] e `of`/`toPlayerView` nunca o tocavam, então todo convidado recebia
 * `dealGuess = null` sempre, em qualquer fase (ver `GameViewModel.dealGuessPrompt`/`handleDealGuessTransition`, no
 * `:app`: a fase `DEAL_GUESS_MORTO` trava porque depende de `dealGuess?.hand`, só disponível agora).
 */
class SeatViewTest {

    private val rules = TableRules("Mesa", GameMode.INDIVIDUAL, RuleSet.DEFAULT, 1000, 0, "facil")

    /** Vista mínima de um assento em [Phase.DEAL_GUESS_MORTO], com o palpite da mão já resolvido (§3.6). */
    private fun mortoGuessView(): PlayerView = PlayerView(
        mode = GameMode.INDIVIDUAL, seat = Seat(1), side = Side(1), currentSeat = Seat(1), firstSeat = Seat(1),
        phase = Phase.DEAL_GUESS_MORTO, result = null, hand = emptyList(), handSizes = listOf(11, 11),
        discardPile = emptyList(), stockSize = 40,
        mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available), mortoSizes = listOf(11, 11),
        redThrees = List(2) { emptyList() }, tables = listOf(SideTable(), SideTable()),
        rules = RuleSet.DEFAULT,
        dealGuess = DealGuess(dealerSeat = Seat(0), mortoDealerSeat = Seat(1), hand = Guess(0, 22, correct = true)),
    )

    private fun seatViewOf(view: PlayerView): SeatView = SeatView.of(
        view = view, roundNumber = 2, stage = TableStage.PLAYING, totals = listOf(0, 0), winner = null, history = null,
        turnEvents = List(2) { emptyList() }, turnRedThrees = List(2) { emptyList() }, thinkingSeat = null,
        legal = listOf(com.gtranca.engine.model.dealGuessAction(22)), requestId = 7,
    )

    @Test
    fun `§3_6 SeatView_of leva o dealGuess da PlayerView, antes perdido`() {
        val view = mortoGuessView()
        val seatView = seatViewOf(view)
        seatView.dealGuess shouldBe view.dealGuess
    }

    @Test
    fun `§3_6 toPlayerView reconstroi o dealGuess, destravando o palpite do morto`() {
        val view = mortoGuessView()
        val seatView = seatViewOf(view)
        val rebuilt = seatView.toPlayerView(RuleSet.DEFAULT)
        rebuilt.dealGuess shouldBe view.dealGuess
        // A condição exata que travava `GameViewModel.dealGuessPrompt` na fase do morto.
        rebuilt.dealGuess?.hand.shouldNotBeNull()
    }

    @Test
    fun `§3_6 §16_4 o dealGuess sobrevive a ida e volta pela rede (Codec, protocolo 20)`() {
        val view = mortoGuessView()
        val seatView = seatViewOf(view)
        val bytes = ProtocolCodec().encode(1, ViewUpdate(seatView))
        val decoded = ProtocolCodec().decode(bytes)
        decoded.shouldBeInstanceOf<Decoded.Ok>()
        val payload = decoded.envelope.p
        payload.shouldBeInstanceOf<ViewUpdate>()
        val received = payload.view
        received.dealGuess shouldBe view.dealGuess
        received.toPlayerView(RuleSet.DEFAULT).dealGuess?.hand?.correct shouldBe true
    }

    @Test
    fun `§3_6 sem palpite (regra desligada ou fase normal) o campo continua null, como antes`() {
        val view = mortoGuessView().copy(dealGuess = null, phase = Phase.PLAYING)
        val seatView = seatViewOf(view)
        seatView.dealGuess.shouldBeNull()
        seatView.toPlayerView(RuleSet.DEFAULT).dealGuess.shouldBeNull()
    }

    /**
     * §3.6 (15/11/2026): o estado de verdade enviado logo no início de toda partida nova (1ª vista, antes de
     * qualquer palpite resolvido) — `hand` e `morto` ainda `null`. Nenhum outro teste deste arquivo cobria esse
     * caso específico (todos usam `hand` já resolvido, ou `dealGuess = null` inteiro) — checado depois de um
     * relato de travamento real no multijogador logo ao iniciar uma partida nova.
     */
    @Test
    fun `§3_6 primeira vista da partida (hand e morto ainda null) sobrevive a ida e volta pela rede`() {
        val view = mortoGuessView().copy(
            phase = Phase.DEAL_GUESS_HAND,
            dealGuess = DealGuess(dealerSeat = Seat(1), mortoDealerSeat = Seat(0), hand = null, morto = null),
        )
        val seatView = seatViewOf(view)
        val bytes = ProtocolCodec().encode(1, ViewUpdate(seatView))
        val decoded = ProtocolCodec().decode(bytes)
        decoded.shouldBeInstanceOf<Decoded.Ok>()
        val payload = decoded.envelope.p
        payload.shouldBeInstanceOf<ViewUpdate>()
        val received = payload.view
        received.dealGuess shouldBe view.dealGuess
        val rebuilt = received.toPlayerView(RuleSet.DEFAULT)
        rebuilt.dealGuess shouldBe view.dealGuess
    }
}
