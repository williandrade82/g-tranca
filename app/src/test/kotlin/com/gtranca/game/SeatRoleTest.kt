package com.gtranca.game

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.ui.cards.CardEmphasis
import com.gtranca.ui.game.handEmphasis
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SeatRoleTest {

    @Test
    fun `§1_1 e §4_2 papeis dos assentos em duplas a partir do humano no assento 0`() {
        val roles = GameMode.DUPLAS.seats.map { SeatRole.of(GameMode.DUPLAS, it, Seat(0)) }
        // §4.2 anti-horário: o assento 1 joga logo depois de você (à direita, recebe o seu descarte);
        // o parceiro senta em frente (2); o assento 3 joga logo antes (à esquerda, você recebe o descarte dele).
        roles shouldBe listOf(SeatRole.YOU, SeatRole.RIGHT_OPPONENT, SeatRole.PARTNER, SeatRole.LEFT_OPPONENT)
        // O parceiro é do mesmo lado; os da esquerda e da direita, do outro.
        GameMode.DUPLAS.sideOf(Seat(2)) shouldBe GameMode.DUPLAS.sideOf(Seat(0))
        SeatRole.LEFT_OPPONENT.isOpponent shouldBe true
        SeatRole.PARTNER.isOpponent shouldBe false
    }

    @Test
    fun `§1_1 e §4_2 papeis relativos a outro assento e no individual`() {
        SeatRole.of(GameMode.DUPLAS, Seat(0), Seat(3)) shouldBe SeatRole.RIGHT_OPPONENT
        SeatRole.of(GameMode.DUPLAS, Seat(1), Seat(3)) shouldBe SeatRole.PARTNER
        SeatRole.of(GameMode.INDIVIDUAL, Seat(1), Seat(0)) shouldBe SeatRole.OPPONENT
        SeatRole.of(GameMode.INDIVIDUAL, Seat(0), Seat(0)) shouldBe SeatRole.YOU
    }

    @Test
    fun `realce da mao - sutil sem selecao, forte ou esmaecido com selecao`() {
        handEmphasis(selected = false, highlighted = true, hasSelection = false, humanTurn = true) shouldBe CardEmphasis.SUBTLE
        handEmphasis(selected = false, highlighted = false, hasSelection = false, humanTurn = true) shouldBe CardEmphasis.NONE
        handEmphasis(selected = false, highlighted = true, hasSelection = true, humanTurn = true) shouldBe CardEmphasis.STRONG
        handEmphasis(selected = false, highlighted = false, hasSelection = true, humanTurn = true) shouldBe CardEmphasis.DIMMED
        handEmphasis(selected = true, highlighted = false, hasSelection = true, humanTurn = true) shouldBe CardEmphasis.SELECTED
        // Fora da vez não há jogadas legais: só a seleção aparece.
        handEmphasis(selected = false, highlighted = false, hasSelection = true, humanTurn = false) shouldBe CardEmphasis.NONE
    }
}
