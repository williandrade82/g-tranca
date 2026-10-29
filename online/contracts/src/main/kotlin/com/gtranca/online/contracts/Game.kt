package com.gtranca.online.contracts

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
import kotlinx.serialization.json.Json

/*
 * Protocolo da partida online (§18.3). Canal: WebSocket em `/ws/match/{tableId}`.
 *
 * Handshake (HTTP, antes do upgrade): `X-Protocol-Version: <n>` (§18.8) e `Authorization: Bearer <token de convidado>`.
 * O token NUNCA vai na URL (logs do ingress). Recusas do handshake sao [ApiError] com status HTTP.
 *
 * Cliente -> servidor: so [PlayAction] (a jogada que quer fazer) e [Resign]. Servidor -> cliente: so a VISTA DO
 * ASSENTO ([SeatUpdate]) e avisos. Nunca GameState/Match, nem mao alheia, monte, mortos ou semente (§18.3, §16.4).
 */

/** Json do protocolo: o mesmo no servidor, nos testes e no `:online-client`. */
val OnlineJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Cabecalho de servico a servico (segredo compartilhado, vindo de variavel de ambiente/Secret; nunca no codigo). */
const val INTERNAL_SECRET_HEADER = "X-Internal-Secret"

/** Limites do canal da partida. */
object GameLimits {
    /** Tamanho maximo de uma mensagem do cliente (bytes de texto). Uma [PlayAction] tem poucas centenas. */
    const val MAX_CLIENT_MESSAGE_BYTES = 16 * 1024
}

/** Perfis de robo (§14.5). */
@Serializable
enum class BotProfile { JOVEM, SOBRIO, ALTERNATIVO, ATLETA }

/** Nivel dos robos (§14.5): Facil = Iniciante, Medio = Maduro, Dificil = Senior. */
@Serializable
enum class BotLevel { FACIL, MEDIO, DIFICIL }

// ---------------------------------------------------------------------------------------------------------------
// Servico a servico (rede interna do cluster, protegido por INTERNAL_SECRET_HEADER)
// ---------------------------------------------------------------------------------------------------------------

/** Quem ocupa um assento ao iniciar a partida. */
@Serializable
sealed interface SeatSpec {
    @Serializable
    @SerialName("human")
    data class Human(val guestId: String, val name: String) : SeatSpec

    @Serializable
    @SerialName("robot")
    data class Robot(val profile: BotProfile, val level: BotLevel) : SeatSpec
}

/**
 * lobby -> game: cria a partida. [seats] tem um item por assento (o indice e o do motor). [rules] traz o `rulesJson`
 * (validado pelo game-service com o motor). [seed] opcional: so para testes reproduziveis.
 */
@Serializable
data class CreateMatchRequest(
    val tableId: String,
    val rules: TableRules,
    val seats: List<SeatSpec>,
    val seed: Long? = null,
)

@Serializable
data class CreateMatchResponse(val tableId: String)

/** game -> lobby: de quem e este token? (o corpo, nao a URL, leva o token). */
@Serializable
data class ResolveGuestRequest(val token: String)

@Serializable
data class ResolvedGuest(val guestId: String, val name: String)

// ---------------------------------------------------------------------------------------------------------------
// Cliente -> servidor
// ---------------------------------------------------------------------------------------------------------------

@Serializable
sealed interface ClientMessage

/**
 * A jogada que o jogador quer fazer. [seq] cresce a cada mensagem do jogador (1, 2, ...), inclusive entre conexoes
 * (o [Welcome] devolve o ultimo valor visto): igual ou menor que o ultimo e descartada, sem efeito. [stateVersion] e a
 * versao da vista em que o jogador se baseou ([SeatUpdate.version]); diferente da atual = [RejectReason.STALE_STATE].
 * Uma mensagem recusada consome o seu [seq].
 */
@Serializable
@SerialName("play")
data class PlayAction(val seq: Long, val stateVersion: Long, val action: Action) : ClientMessage

/** Desistir (§13.1, §18.4): um robo assume o assento e o jogo segue para os demais. */
@Serializable
@SerialName("resign")
data class Resign(val seq: Long) : ClientMessage

// ---------------------------------------------------------------------------------------------------------------
// Servidor -> cliente
// ---------------------------------------------------------------------------------------------------------------

@Serializable
sealed interface ServerMessage

/** Como um assento aparece a todos (sem nada oculto). */
@Serializable
data class SeatInfo(
    val index: Int,
    val kind: SeatKind,
    val name: String,
    /** Robo: perfil e nivel. */
    val profile: BotProfile? = null,
    val level: BotLevel? = null,
    /** Humano: tem conexao agora. Robo: sempre `true`. */
    val connected: Boolean = true,
    /** Humano que desistiu; o assento agora e de um robo ([kind] = ROBO). */
    val resigned: Boolean = false,
)

/**
 * Primeira mensagem da conexao: o seu assento, as regras da mesa (uma vez) e os assentos. [lastSeq] e o ultimo [seq]
 * aceito para voce: o proximo [PlayAction] deve ter mais que ele. Logo depois vem o [SeatUpdate] atual.
 */
@Serializable
@SerialName("welcome")
data class Welcome(
    val protocolVersion: Int,
    val tableId: String,
    val you: Seat,
    val table: TableRules,
    val ruleSet: RuleSet,
    val seats: List<SeatInfo>,
    val lastSeq: Long,
) : ServerMessage

@Serializable
enum class TableStage { PLAYING, ROUND_OVER, GAME_OVER }

/**
 * Vista de UM assento (§16.4, §18.3): a [PlayerView] do assento (a mesma que um robo recebe) sem o perfil de regras
 * (vai no [Welcome]) mais placar, vez e a ultima jogada de cada assento. So a propria mao; dos outros, tamanhos.
 *
 * @property version contador que sobe a cada mudanca; volta em [PlayAction.stateVersion].
 * @property totals total acumulado por lado (por [Side.index]).
 * @property history partidas ja pontuadas (publicas); `null` = igual ao ultimo enviado (so vai no Welcome, no fim de partida e no fim de jogo).
 * @property lastPlays ultima jogada de cada assento NESTA partida (`null` = ainda nao jogou).
 * @property thinkingSeat assento de robo que esta jogando agora (para a pose de pensador).
 * @property legal jogadas legais do assento (vazio se nao e a vez dele): unica fonte para a interface.
 */
@Serializable
data class OnlineSeatView(
    val version: Long,
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
    val lastPlays: List<PublicEvent?> = emptyList(),
    val thinkingSeat: Seat? = null,
    val legal: List<Action> = emptyList(),
) {
    /** A [PlayerView] do assento, com as regras recebidas no [Welcome]. */
    fun toPlayerView(rules: RuleSet): PlayerView = PlayerView(
        mode = mode, seat = seat, side = side, currentSeat = currentSeat, firstSeat = firstSeat, phase = phase,
        result = result, hand = hand, handSizes = handSizes, discardPile = discardPile, stockSize = stockSize,
        mortoStatus = mortoStatus, mortoSizes = mortoSizes, redThrees = redThrees, tables = tables,
        redThreeLog = redThreeLog, turnsBegun = turnsBegun, unsettledMortoSeats = unsettledMortoSeats, rules = rules,
    )
}

/**
 * Cada jogada processada (e cada mudanca de partida) gera um destes para TODOS os humanos da mesa, cada um com a
 * vista do SEU assento. [event] e a jogada que causou a mudanca (publica); `null` em mudanca de partida/entrada.
 */
@Serializable
@SerialName("update")
data class SeatUpdate(val event: PublicEvent? = null, val view: OnlineSeatView) : ServerMessage

@Serializable
enum class SeatChange { CONNECTED, DISCONNECTED, RESIGNED }

/** Presenca/assentos mudaram. [seat] e o assento a que [change] se refere. */
@Serializable
@SerialName("seats")
data class SeatsChanged(val seats: List<SeatInfo>, val seat: Int, val change: SeatChange) : ServerMessage

@Serializable
enum class RejectReason {
    /** Nao ha partida em andamento (entre partidas ou jogo encerrado). */
    NOT_PLAYING,
    NOT_YOUR_TURN,

    /** [PlayAction.stateVersion] nao e a atual. */
    STALE_STATE,

    /** A jogada nao esta em `legalActions` do assento. */
    ILLEGAL,
}

/** Jogada recusada com o motivo; nada muda no jogo. [refusals] = recusas acumuladas deste jogador. */
@Serializable
@SerialName("rejected")
data class Rejected(val seq: Long, val reason: RejectReason, val message: String, val refusals: Int) : ServerMessage

/** A conexao foi substituida por outra do mesmo jogador (§18.4); esta sera fechada. */
@Serializable
@SerialName("replaced")
data class Replaced(val message: String) : ServerMessage

/** Voce desistiu; um robo assumiu o assento. A conexao sera fechada. */
@Serializable
@SerialName("resigned")
data class YouResigned(val message: String) : ServerMessage

/** Erro tipado e fatal para a conexao (mensagem invalida, limite de taxa...). */
@Serializable
@SerialName("error")
data class GameError(val code: ErrorCode, val message: String) : ServerMessage
