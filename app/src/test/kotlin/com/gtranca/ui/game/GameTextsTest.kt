package com.gtranca.ui.game

import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.SeatRole
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Escolha dos textos por modo e por assento (os textos em si ficam em `strings.xml`). */
class GameTextsTest {

    @Test
    fun `§11_1 quem bateu, por papel do assento`() {
        SeatRole.YOU.wentOutRes() shouldBe R.string.round_result_you_went_out
        SeatRole.OPPONENT.wentOutRes() shouldBe R.string.round_result_opponent_went_out
        SeatRole.PARTNER.wentOutRes() shouldBe R.string.round_result_partner_went_out
        SeatRole.LEFT_OPPONENT.wentOutRes() shouldBe R.string.round_result_left_went_out
        SeatRole.RIGHT_OPPONENT.wentOutRes() shouldBe R.string.round_result_right_went_out
    }

    @Test
    fun `§9_1 morto pego, por modo e lado`() {
        mortoTakenRes(GameMode.INDIVIDUAL, own = true) shouldBe R.string.morto_taken_you
        mortoTakenRes(GameMode.INDIVIDUAL, own = false) shouldBe R.string.morto_taken_opponent
        mortoTakenRes(GameMode.DUPLAS, own = true) shouldBe R.string.morto_taken_your_team
        mortoTakenRes(GameMode.DUPLAS, own = false) shouldBe R.string.morto_taken_other_team
    }

    @Test
    fun `§6_4 titulo dos jogos do lado, por modo`() {
        meldsTitleRes(GameMode.INDIVIDUAL, own = true) shouldBe R.string.your_melds
        meldsTitleRes(GameMode.INDIVIDUAL, own = false) shouldBe R.string.opponent_melds
        meldsTitleRes(GameMode.DUPLAS, own = true) shouldBe R.string.team_melds
        meldsTitleRes(GameMode.DUPLAS, own = false) shouldBe R.string.other_team_melds
    }

    @Test
    fun `§13 e §13_1 resultado do jogo, por modo`() {
        gameResultRes(GameMode.INDIVIDUAL, won = true) shouldBe R.string.game_over_you_won
        gameResultRes(GameMode.INDIVIDUAL, won = false) shouldBe R.string.game_over_opponent_won
        gameResultRes(GameMode.DUPLAS, won = true) shouldBe R.string.game_over_team_won
        gameResultRes(GameMode.DUPLAS, won = false) shouldBe R.string.game_over_other_team_won
        resignedRes(GameMode.INDIVIDUAL) shouldBe R.string.game_over_resigned_individual
        resignedRes(GameMode.DUPLAS) shouldBe R.string.game_over_resigned_duplas
    }

    @Test
    fun `§11 banner de fim de partida, por quem bateu e por modo`() {
        roundEndMessageRes(RoundEnd.YOU, GameMode.INDIVIDUAL) shouldBe R.string.go_out_you
        roundEndMessageRes(RoundEnd.PARTNER, GameMode.DUPLAS) shouldBe R.string.go_out_partner
        roundEndMessageRes(RoundEnd.OPPONENT, GameMode.INDIVIDUAL) shouldBe R.string.go_out_lost
        roundEndMessageRes(RoundEnd.OPPONENT, GameMode.DUPLAS) shouldBe R.string.go_out_lost_team
        // §11.2 sem batida: o monte acabou.
        roundEndMessageRes(RoundEnd.NO_WINNER, GameMode.DUPLAS) shouldBe R.string.round_end_stock_out
    }
}
