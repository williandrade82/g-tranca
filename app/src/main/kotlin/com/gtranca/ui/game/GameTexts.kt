package com.gtranca.ui.game

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gtranca.R
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.SeatRole
import com.gtranca.ui.cards.shortLabel

/** Texto pt-BR de cada motivo de recusa do motor (um por valor de [ActionError] e [MeldError]). */
@StringRes
fun RuleError?.messageRes(): Int = when (this) {
    null -> R.string.msg_invalid_generic
    ActionError.ROUND_FINISHED -> R.string.error_round_finished
    ActionError.NOT_YOUR_TURN -> R.string.error_not_your_turn
    ActionError.MUST_DRAW_FIRST -> R.string.error_must_draw_first
    ActionError.ALREADY_DREW -> R.string.error_already_drew
    ActionError.STOCK_EXHAUSTED -> R.string.error_stock_exhausted
    ActionError.DECLINE_NOT_ALLOWED -> R.string.error_decline_not_allowed
    ActionError.DISCARD_PILE_EMPTY -> R.string.error_discard_pile_empty
    ActionError.DISCARD_PILE_LOCKED -> R.string.error_discard_pile_locked
    ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS -> R.string.error_discard_top_needs_two
    ActionError.CARD_NOT_IN_HAND -> R.string.error_card_not_in_hand
    ActionError.CANNOT_DISCARD_RED_THREE -> R.string.error_cannot_discard_red_three
    ActionError.NO_MORTO_AVAILABLE -> R.string.error_no_morto_available
    ActionError.NO_CANASTA_TO_GO_OUT -> R.string.error_no_canasta_to_go_out
    ActionError.MUST_KEEP_CARD_TO_DISCARD -> R.string.error_must_keep_card
    MeldError.TOO_FEW_CARDS -> R.string.error_too_few_cards
    MeldError.NO_CARDS -> R.string.error_no_cards
    MeldError.DUPLICATE_CARD -> R.string.error_duplicate_card
    MeldError.CONTAINS_THREE -> R.string.error_contains_three
    MeldError.TOO_MANY_WILDS -> R.string.error_too_many_wilds
    MeldError.NOT_A_SEQUENCE_OR_GROUP -> R.string.error_not_sequence_or_group
    MeldError.REPEATED_RANK -> R.string.error_repeated_rank
    MeldError.NOT_CONSECUTIVE -> R.string.error_not_consecutive
    MeldError.WRONG_SUIT -> R.string.error_wrong_suit
    MeldError.WRONG_RANK -> R.string.error_wrong_rank
    MeldError.CONTIGUOUS_SEQUENCE -> R.string.error_contiguous_sequence
    MeldError.MELD_NOT_FOUND -> R.string.error_meld_not_found
}

@StringRes
fun UiMessage.messageRes(): Int = when (this) {
    is UiMessage.Rejected -> error.messageRes()
    UiMessage.SelectOneCardToDiscard -> R.string.msg_select_one_to_discard
}

/** Nome do lado do ponto de vista do humano. */
@Composable
@ReadOnlyComposable
fun sideName(mode: GameMode, side: Side, viewerSide: Side): String = when {
    side == viewerSide && mode == GameMode.INDIVIDUAL -> stringResource(R.string.side_you)
    side == viewerSide -> stringResource(R.string.side_your_team)
    mode == GameMode.INDIVIDUAL -> stringResource(R.string.side_opponent)
    else -> stringResource(R.string.side_other_team)
}

/** Nome do assento do ponto de vista do humano (ex.: "Parceiro", "Adversário à esquerda"). */
@StringRes
fun SeatRole.nameRes(): Int = when (this) {
    SeatRole.YOU -> R.string.side_you
    SeatRole.OPPONENT -> R.string.side_opponent
    SeatRole.PARTNER -> R.string.seat_partner
    SeatRole.LEFT_OPPONENT -> R.string.seat_left_opponent
    SeatRole.RIGHT_OPPONENT -> R.string.seat_right_opponent
}

/** "Vez de …" de um assento que não é o humano. */
@StringRes
fun SeatRole.turnRes(): Int = when (this) {
    SeatRole.YOU, SeatRole.OPPONENT -> R.string.turn_opponent
    SeatRole.PARTNER -> R.string.turn_partner
    SeatRole.LEFT_OPPONENT -> R.string.turn_left_opponent
    SeatRole.RIGHT_OPPONENT -> R.string.turn_right_opponent
}

/** Quem bateu (§11.1), pelo assento. */
@StringRes
fun SeatRole.wentOutRes(): Int = when (this) {
    SeatRole.YOU -> R.string.round_result_you_went_out
    SeatRole.OPPONENT -> R.string.round_result_opponent_went_out
    SeatRole.PARTNER -> R.string.round_result_partner_went_out
    SeatRole.LEFT_OPPONENT -> R.string.round_result_left_went_out
    SeatRole.RIGHT_OPPONENT -> R.string.round_result_right_went_out
}

/** Título da área de jogos de um lado. */
@StringRes
fun meldsTitleRes(mode: GameMode, own: Boolean): Int = when {
    mode == GameMode.INDIVIDUAL && own -> R.string.your_melds
    mode == GameMode.INDIVIDUAL -> R.string.opponent_melds
    own -> R.string.team_melds
    else -> R.string.other_team_melds
}

/** Situação de morto pego por um lado. */
@StringRes
fun mortoTakenRes(mode: GameMode, own: Boolean): Int = when {
    mode == GameMode.INDIVIDUAL && own -> R.string.morto_taken_you
    mode == GameMode.INDIVIDUAL -> R.string.morto_taken_opponent
    own -> R.string.morto_taken_your_team
    else -> R.string.morto_taken_other_team
}

/** Resultado do jogo (§13) para o lado do humano. */
@StringRes
fun gameResultRes(mode: GameMode, won: Boolean): Int = when {
    mode == GameMode.INDIVIDUAL && won -> R.string.game_over_you_won
    mode == GameMode.INDIVIDUAL -> R.string.game_over_opponent_won
    won -> R.string.game_over_team_won
    else -> R.string.game_over_other_team_won
}

/** Nome curto do lado para o placar do topo ("Você"/"Adversário"; em duplas, "Nós"/"Eles"). */
@Composable
@ReadOnlyComposable
fun shortSideName(mode: GameMode, side: Side, viewerSide: Side): String = when (mode) {
    GameMode.INDIVIDUAL -> sideName(mode, side, viewerSide)
    GameMode.DUPLAS -> stringResource(if (side == viewerSide) R.string.score_us else R.string.score_them)
}

fun List<Card>.labels(): String = joinToString(" ") { it.shortLabel }

/** Descrição de uma ação pública (ex.: "baixou 7♥ 8♥ 9♥"). */
@Composable
@ReadOnlyComposable
fun eventText(event: PublicEvent): String = when (val action = event.action) {
    Action.DrawFromStock -> stringResource(R.string.event_draw)
    Action.DeclineDraw -> stringResource(R.string.event_decline)
    is Action.TakeDiscardPile -> {
        val plan = action.plan
        val handCards = when (plan) {
            is DiscardPlan.NewMeld -> plan.handCards
            is DiscardPlan.AddToMeld -> plan.handCards
        }
        countText(R.plurals.event_take_discard, R.string.event_take_discard_zero, event.takenFromDiscard.size) +
            if (handCards.isNotEmpty()) " (" + handCards.labels() + ")" else ""
    }
    is Action.CreateMeld -> stringResource(R.string.event_create_meld, action.cards.labels())
    is Action.AddToMeld -> stringResource(R.string.event_add_to_meld, action.cards.labels())
    is Action.Discard -> stringResource(R.string.event_discard, action.card.shortLabel)
}

@Composable
@ReadOnlyComposable
fun turnText(events: List<PublicEvent>): String =
    events.map { eventText(it) }.joinToString(stringResource(R.string.event_separator))

/**
 * Texto com quantidade: [zeroRes] para 0 (em pt, a forma `one` dos plurais cobre o 0 e daria "0 carta"); o plural
 * [pluralsRes] (com a quantidade como 1º argumento) para os demais.
 */
@Composable
@ReadOnlyComposable
fun countText(@PluralsRes pluralsRes: Int, @StringRes zeroRes: Int, count: Int): String =
    if (count == 0) stringResource(zeroRes) else pluralStringResource(pluralsRes, count, count)

/** §11 anúncio do resultado da partida, antes dos pontos. */
@StringRes
fun roundAnnouncementRes(mode: GameMode, result: RoundResult, viewerSeat: Seat): Int = when (result) {
    RoundResult.NoWinner -> R.string.announce_no_winner
    is RoundResult.GoOut -> SeatRole.of(mode, result.seat, viewerSeat).wentOutRes()
}

/** Em duplas, a frase complementar do anúncio ("Sua dupla venceu a partida!"); `null` no individual ou sem vencedor. */
@StringRes
fun roundTeamAnnouncementRes(mode: GameMode, result: RoundResult, viewerSide: Side): Int? = when {
    mode != GameMode.DUPLAS || result !is RoundResult.GoOut -> null
    result.side == viewerSide -> R.string.announce_team_won_round
    else -> R.string.announce_other_team_won_round
}

/** §13.1 texto do fim de jogo por desistência. */
@StringRes
fun resignedRes(mode: GameMode): Int =
    if (mode == GameMode.DUPLAS) R.string.game_over_resigned_duplas else R.string.game_over_resigned_individual

/** §3.5/§6.5 título da encenação de troca de 3 vermelho do próprio humano. */
@Composable
@ReadOnlyComposable
fun revealTitle(notice: RedThreeNotice): String {
    val cards = notice.cards.labels()
    return stringResource(if (notice.atTurnStart) R.string.red_three_turn_you else R.string.red_three_you_drew, cards)
}

/** §3.5/§6.5 aviso da troca de 3 vermelho de outro assento: quem, qual carta e se foi no início da vez. */
@Composable
@ReadOnlyComposable
fun bannerText(mode: GameMode, notice: RedThreeNotice, viewerSeat: Seat): String {
    val name = stringResource(SeatRole.of(mode, notice.seat, viewerSeat).nameRes())
    return stringResource(if (notice.atTurnStart) R.string.red_three_banner_turn else R.string.red_three_banner, name, notice.cards.labels())
}
