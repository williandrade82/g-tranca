package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versão do protocolo do multijogador local (§16.1). Aparelhos com versões diferentes **não jogam juntos**: quem recebe
 * uma mensagem de outra versão recusa com [VERSION_MISMATCH_MESSAGE]. Suba a versão a cada mudança incompatível das
 * mensagens.
 */
const val PROTOCOL_VERSION: Int = 2

/** §16.1 texto mostrado a quem tenta jogar com outra versão. */
const val VERSION_MISMATCH_MESSAGE: String = "Versão do jogo diferente: atualize o app nos dois celulares"

/**
 * Envelope de toda mensagem. [v] é a versão do protocolo (lida **antes** do resto, para recusar outra versão com clareza);
 * [seq] é o número de sequência do remetente naquela conexão (1, 2, ...): o receptor descarta duplicadas e atrasadas e
 * entrega em ordem (§16.5).
 */
@Serializable
data class Envelope(val v: Int, val seq: Long, val p: Payload)

/** Identificação pública de um participante (§16.5): todos conhecem; **sem** o token. */
@Serializable
data class PlayerInfo(
    val id: Int,
    val name: String,
    /** Ordem de entrada na mesa (0 = anfitrião). */
    val joinOrder: Int,
    val seat: Seat? = null,
    val isHost: Boolean = false,
    /** §16.2 confirmou a posição ("Aguardando início do jogo"); o anfitrião já entra confirmado. */
    val confirmed: Boolean = false,
)

/**
 * Regras da mesa (§16.2): vão **uma única vez**, ao entrar, e não mudam até o fim do jogo. [difficulty] é o id da
 * dificuldade dos robôs (`Difficulty.id` do `:ai`); [robots] são os ids dos personagens escolhidos pelo anfitrião.
 */
@Serializable
data class TableRules(
    val tableName: String,
    val mode: GameMode,
    val rules: RuleSet,
    val targetScore: Int,
    val stake: Int,
    val difficulty: String,
    val robots: List<String> = emptyList(),
)

/** Etapa do jogo vista por um assento (mesma ideia de `Stage` do `:app`). */
@Serializable
enum class TableStage { PLAYING, ROUND_OVER, GAME_OVER }

/** Motivo de uma jogada recusada (§16.1); nenhuma recusa altera o jogo. */
@Serializable
enum class RejectReason {
    /** O jogo ainda não começou (ou já terminou). */
    NOT_PLAYING,

    /** Não há pedido em aberto para o assento de quem enviou. */
    NOT_YOUR_TURN,

    /** O pedido não é o em aberto: jogada antiga, repetida ou de outro assento. */
    STALE_REQUEST,

    /** A jogada não está nas jogadas legais; [ActionRejected.ruleError] traz o motivo do motor, quando houver. */
    ILLEGAL,
}

/**
 * Vista de **um** assento (§16.4), enviada só ao celular daquele assento: a [PlayerView] (a mesma que um robô recebe,
 * §14.5) **sem** o perfil de regras (ele foi uma vez, em [TableRules]) mais placar, vez e a última jogada de cada
 * assento. Nunca mãos alheias, monte, mortos (só tamanhos e situação) nem semente.
 *
 * @property history partidas pontuadas; `null` = igual à última enviada (não se reenvia o que não mudou).
 * @property legal jogadas legais do pedido em aberto a este assento (vazio se não é a vez dele).
 * @property requestId pedido em aberto a que [legal] se refere (0 sem pedido); volta em [ProposeAction].
 */
@Serializable
data class SeatView(
    val roundNumber: Int,
    val stage: TableStage,
    val totals: List<Int>,
    val winner: Side? = null,
    val history: List<RoundRecord>? = null,
    val mode: GameMode,
    val seat: Seat,
    val side: Side,
    val currentSeat: Seat,
    val firstSeat: Seat,
    val phase: Phase,
    val result: RoundResult? = null,
    val hand: List<Card>,
    val handSizes: List<Int>,
    val discardPile: List<Card>,
    val stockSize: Int,
    val mortoStatus: List<MortoStatus>,
    val mortoSizes: List<Int>,
    val redThrees: List<List<Card>>,
    val tables: List<SideTable>,
    val redThreeLog: List<RedThreeLaid> = emptyList(),
    val turnsBegun: Int,
    val unsettledMortoSeats: List<Seat> = emptyList(),
    val turnEvents: List<List<PublicEvent>> = emptyList(),
    val turnRedThrees: List<List<Card>> = emptyList(),
    val thinkingSeat: Seat? = null,
    val legal: List<Action> = emptyList(),
    val requestId: Long = 0,
) {
    /** A [PlayerView] do assento, com as regras da mesa recebidas uma vez ([TableRules.rules]). */
    fun toPlayerView(rules: RuleSet): PlayerView = PlayerView(
        mode = mode, seat = seat, side = side, currentSeat = currentSeat, firstSeat = firstSeat, phase = phase,
        result = result, hand = hand, handSizes = handSizes, discardPile = discardPile, stockSize = stockSize,
        mortoStatus = mortoStatus, mortoSizes = mortoSizes, redThrees = redThrees, tables = tables,
        redThreeLog = redThreeLog, turnsBegun = turnsBegun, unsettledMortoSeats = unsettledMortoSeats, rules = rules,
    )

    companion object {
        /** Monta a vista a partir da [PlayerView] do assento (`viewFor(seat)`), deixando de fora as regras. */
        fun of(
            view: PlayerView,
            roundNumber: Int,
            stage: TableStage,
            totals: List<Int>,
            winner: Side?,
            history: List<RoundRecord>?,
            turnEvents: List<List<PublicEvent>>,
            turnRedThrees: List<List<Card>>,
            thinkingSeat: Seat?,
            legal: List<Action>,
            requestId: Long,
        ): SeatView = SeatView(
            roundNumber = roundNumber, stage = stage, totals = totals, winner = winner, history = history,
            mode = view.mode, seat = view.seat, side = view.side, currentSeat = view.currentSeat, firstSeat = view.firstSeat,
            phase = view.phase, result = view.result, hand = view.hand, handSizes = view.handSizes,
            discardPile = view.discardPile, stockSize = view.stockSize, mortoStatus = view.mortoStatus,
            mortoSizes = view.mortoSizes, redThrees = view.redThrees, tables = view.tables, redThreeLog = view.redThreeLog,
            turnsBegun = view.turnsBegun, unsettledMortoSeats = view.unsettledMortoSeats, turnEvents = turnEvents,
            turnRedThrees = turnRedThrees, thinkingSeat = thinkingSeat, legal = legal, requestId = requestId,
        )
    }
}

/** Conteúdo de uma mensagem. Convidado → anfitrião: [Hello], sala, [ProposeAction], [Resign]. O resto vai do anfitrião. */
@Serializable
sealed interface Payload

/** Convidado pede para entrar com o [name] (§16.2; sem foto, sem cor do baralho). */
@Serializable
@SerialName("hello")
data class Hello(val name: String) : Payload

/** Entrada aceita: o [token] (só deste jogador, §16.5), o id público dele, as regras da mesa (uma vez) e a sala. */
@Serializable
@SerialName("welcome")
data class Welcome(val token: String, val you: Int, val rules: TableRules, val roster: List<PlayerInfo>) : Payload

/** Entrada ou mensagem recusada, com o motivo em pt-BR (ex.: [VERSION_MISMATCH_MESSAGE]). */
@Serializable
@SerialName("rejected")
data class Rejected(val message: String) : Payload

/** Convidado troca para um assento **vago** (§16.2); ao entrar ele já ocupa o primeiro livre. */
@Serializable
@SerialName("chooseSeat")
data class ChooseSeat(val seat: Seat) : Payload

/** §16.2 convidado confirma a posição ("Aguardando início do jogo"). */
@Serializable
@SerialName("confirmSeat")
data object ConfirmSeat : Payload

/** §16.9 convidado desiste; aceito a qualquer momento depois do início (não depende da vez). */
@Serializable
@SerialName("resign")
data object Resign : Payload

/** §16.9 o jogador do [seat] desistiu e um robô assumiu o assento (aviso público a todos). */
@Serializable
@SerialName("seatToRobot")
data class SeatTakenByRobot(val seat: Seat) : Payload

/** Convidado muda o nome (§16.2). */
@Serializable
@SerialName("rename")
data class Rename(val name: String) : Payload

/** Convidado sai da sala antes do início. */
@Serializable
@SerialName("leave")
data object Leave : Payload

/** Sala atualizada, redistribuída a todos a cada alteração (§16.2). */
@Serializable
@SerialName("roster")
data class RosterUpdate(val roster: List<PlayerInfo>) : Payload

/** O anfitrião iniciou o jogo; assentos sem humano ficam com robôs (§16.2). */
@Serializable
@SerialName("started")
data class GameStarted(val roster: List<PlayerInfo>) : Payload

/** Vista do assento do destinatário (§16.4). */
@Serializable
@SerialName("view")
data class ViewUpdate(val view: SeatView) : Payload

/** §16.6 uma partida foi distribuída. */
@Serializable
@SerialName("roundStarted")
data class RoundStarted(val roundNumber: Int) : Payload

/** §16.6 uma jogada processada pelo anfitrião (só informação pública), para cada aparelho animar no seu ritmo. */
@Serializable
@SerialName("played")
data class Played(val roundNumber: Int, val event: PublicEvent, val redThrees: List<RedThreeLaid> = emptyList()) : Payload

/**
 * §16.11 pacote de fim de partida, enviado **só** ao convidado do assento de [view]: o detalhamento §12 ([record], só
 * contagens e pontos), os [totals], o vencedor do jogo se ele terminou ([gameWinner], §13) e a mesa final vista desse
 * assento (só a mão dele). Nada de mãos alheias, monte, mortos ou semente (§16.4). O convidado conduz sozinho as telas de
 * resultado; o que chegar da partida seguinte fica guardado até ele avançar ([GuestSession.advance]).
 */
@Serializable
@SerialName("roundOver")
data class RoundOver(
    val roundNumber: Int,
    val record: RoundRecord,
    val totals: List<Int>,
    val gameWinner: Side? = null,
    val view: SeatView,
) : Payload

/** §13 o jogo terminou. */
@Serializable
@SerialName("finished")
data class GameFinished(val winner: Side, val resigned: Boolean = false) : Payload

/** Convidado propõe a jogada [action] para o pedido [requestId] da vista dele (§16.1). O assento vem da sessão. */
@Serializable
@SerialName("propose")
data class ProposeAction(val requestId: Long, val action: Action) : Payload

/** §16.1 jogada recusada sem alterar o jogo; [ruleError] é o nome do erro do motor (`ActionError`/`MeldError`), se houver. */
@Serializable
@SerialName("actionRejected")
data class ActionRejected(val requestId: Long, val reason: RejectReason, val ruleError: String? = null) : Payload
