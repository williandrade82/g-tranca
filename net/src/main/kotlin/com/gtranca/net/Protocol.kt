package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.RuleSet
import com.gtranca.engine.GroupBetLedger
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Match
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
const val PROTOCOL_VERSION: Int = 12

/** §16.1 texto mostrado a quem tenta jogar com outra versão (sem saber as versões). */
const val VERSION_MISMATCH_MESSAGE: String =
    "O anfitrião está com outra versão do jogo. Atualizem todos os celulares para a mesma versão e criem uma mesa nova."

/**
 * §16.1 o mesmo aviso com as versões de comunicação dos dois lados (a do anfitrião, quando conhecida, e a deste
 * aparelho), para quem atualiza saber qual celular está atrasado.
 */
fun versionMismatchMessage(hostVersion: Int?, mine: Int): String =
    "O anfitrião está com outra versão do jogo (anfitrião: versão ${hostVersion ?: "?"}; você: versão $mine). " +
        "Atualizem todos os celulares para a mesma versão e criem uma mesa nova."

/** §16.5 sessão duplicada: o mesmo jogador entrou por outra conexão, que assumiu o assento. */
const val REPLACED_MESSAGE: String = "Você voltou à mesa por outra conexão; esta foi encerrada."

/**
 * Envelope de toda mensagem. [v] é a versão do protocolo (lida **antes** do resto, para recusar outra versão com clareza);
 * [seq] é o número de sequência do remetente naquela conexão (1, 2, ...): o receptor descarta duplicadas e atrasadas e
 * entrega em ordem (§16.5). `seq = 0` marca uma mensagem de **controle** ([AckOnly], [ResendRequest]), fora da sequência.
 * [ack] é a última mensagem recebida em ordem pelo remetente: o outro lado descarta o que já foi confirmado e reenvia o
 * resto (§16.5 a perdida é reenviada).
 */
@Serializable
data class Envelope(val v: Int, val seq: Long, val p: Payload, val ack: Long = 0)

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
    /** §16.8 conectado agora; `false` = caiu e o assento está reservado a ele (pode reingressar com o token). */
    val connected: Boolean = true,
    /** §16.9 desistiu: o assento aparece como "VAGO" até o anfitrião decidir na vez dele. */
    val resigned: Boolean = false,
    /** §16.10 posição na sucessão (1 ou 2); 0 = não é sucessor. */
    val successorRank: Int = 0,
    /** §16.2 convidado que ainda não respondeu ao convite (aceito sozinho em 10 s). */
    val invited: Boolean = false,
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
    /** Depois de quantas ações da vez cada 3 vermelho de [turnRedThrees] foi baixado (ordem real da vez). */
    val turnRedThreeAfter: List<List<Int>> = emptyList(),
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
            turnRedThreeAfter: List<List<Int>> = emptyList(),
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
            turnRedThrees = turnRedThrees, turnRedThreeAfter = turnRedThreeAfter, thinkingSeat = thinkingSeat, legal = legal, requestId = requestId,
        )
    }
}

/** Conteúdo de uma mensagem. Convidado → anfitrião: [Hello], sala, [ProposeAction], [Resign]. O resto vai do anfitrião. */
@Serializable
sealed interface Payload

/**
 * Convidado pede para entrar com o [name] (§16.2; sem foto, sem cor do baralho). Com [token] (e o [seat] que ele
 * ocupava) é o **reingresso** (§16.5); sem token, com o jogo em andamento, é um pedido de entrada no meio (§16.7).
 */
@Serializable
@SerialName("hello")
data class Hello(
    val name: String,
    val token: String? = null,
    val seat: Seat? = null,
    /** §16.5 última partida cujo pacote de fim (§16.11) este aparelho recebeu; o anfitrião reenvia o que faltar. */
    val roundOverSeen: Int = 0,
    /** §16.2 foto do perfil, pequena e comprimida, em Base64 (JPEG); `null` = sem foto. Vai uma vez por conexão. */
    val photo: String? = null,
    /** §16.2 chave de presença de quem foi convidado (achou-a em [TableAdvert.invited]): o anfitrião responde com o [Invite]. */
    val inviteKey: String? = null,
) : Payload {
    override fun toString(): String = "Hello(name=$name, seat=$seat, photo=${photo?.length ?: 0})" // nunca registrar o token
}

/**
 * §16.2 foto do perfil do jogador [playerId] (Base64, JPEG pequeno), repassada pelo anfitrião **uma vez** a cada aparelho
 * da mesa (na entrada de cada um e a quem volta ou entra no meio). Quem recebe a guarda só em memória.
 */
@Serializable
@SerialName("photo")
data class PlayerPhoto(val playerId: Int, val photo: String) : Payload {
    override fun toString(): String = "PlayerPhoto($playerId, ${photo.length})"
}

/**
 * §16.2 troca de fotos entre dois jogadores próximos (fora de mesa), numa conexão curta aberta pelo de menor chave: cada
 * lado envia a própria [key] de presença (para o outro saber de quem é) e a [photo] (Base64, JPEG pequeno; `null` = sem
 * foto, vale a inicial). Nada mais trafega nessa conexão.
 */
@Serializable
@SerialName("presencePhoto")
data class PresencePhoto(val key: String, val photo: String? = null) : Payload {
    override fun toString(): String = "PresencePhoto(${photo?.length ?: 0})"
}

/** §16.2 participante da montagem do anfitrião, como o recrutado o vê (só nome; [persona] para robô). */
@Serializable
data class RecruitParticipant(val name: String, val persona: String? = null, val accepted: Boolean = true)

/**
 * §16.2 a montagem do anfitrião, sincronizada ao vivo com quem aceitou o recrutamento (só para leitura): modo, nível dos
 * robôs, aposta, regras completas, pontuação-alvo e participantes. Nada oculto (ainda não há cartas).
 */
@Serializable
data class RecruitConfig(
    val hostName: String,
    val mode: GameMode,
    val difficulty: String,
    val stake: Int,
    val rules: RuleSet,
    val targetScore: Int,
    val participants: List<RecruitParticipant>,
)

/** §16.2 resposta ao convite imediato (recrutado → anfitrião, primeira mensagem da conexão). */
@Serializable
@SerialName("recruitReply")
data class RecruitReply(val key: String, val accepted: Boolean, val reason: RefusalReason? = null) : Payload

/** §16.2 a montagem do anfitrião mudou (anfitrião → recrutados que aceitaram). */
@Serializable
@SerialName("recruitSync")
data class RecruitSync(val config: RecruitConfig) : Payload

/** §16.2 o recrutado saiu da mesa ("Sair da mesa", `null`) ou saiu sozinho ([RefusalReason.INSUFFICIENT_BALANCE]). */
@Serializable
@SerialName("recruitLeave")
data class RecruitLeave(val reason: RefusalReason? = null) : Payload

/** §16.2 por que o recrutamento acabou do lado do anfitrião. */
@Serializable
enum class RecruitEndReason {
    /** "<anfitrião> tirou você da mesa". */
    REMOVED,

    /** "Mesa encerrada": o anfitrião saiu da montagem ou desligou o Wi-Fi do jogo. */
    CLOSED,
}

/** §16.2 o anfitrião encerrou o recrutamento deste jogador. */
@Serializable
@SerialName("recruitEnd")
data class RecruitEnd(val reason: RecruitEndReason) : Payload

/** §16.2 o anfitrião tocou em Escolher posições: entrar na mesa [tableId] com a própria chave (sem novo convite). */
@Serializable
@SerialName("recruitStart")
data class RecruitStart(val tableId: String) : Payload

/** Limite do texto Base64 da foto aceito na mesa (~15 KB de JPEG); maior é descartado. */
const val MAX_PHOTO_BASE64: Int = 20_480


/**
 * Entrada aceita: o [token] (só deste jogador, §16.5), o id público dele, as regras da mesa (uma vez: `null` quando já
 * foram no [Invite]) e a sala.
 */
@Serializable
@SerialName("welcome")
data class Welcome(
    val token: String,
    val you: Int,
    val rules: TableRules? = null,
    val roster: List<PlayerInfo>,
    /** O jogo já está em andamento (reingresso ou entrada no meio, §16.5, §16.7): o convidado vai direto à mesa. */
    val started: Boolean = false,
    /** Com o jogo em andamento: o personagem de cada assento de robô (índice do assento → id), escolhido pelo anfitrião. */
    val robots: Map<Int, String> = emptyMap(),
    /** §16.2 escolha de posições em andamento (mesa montada, §14.4); `null` nas mesas antigas ou com o jogo começado. */
    val seating: SeatingState? = null,
) : Payload {
    override fun toString(): String = "Welcome(you=$you)" // nunca registrar o token
}

/** §16.2 motivo de uma recusa do convite ou do pedido de entrada, informado aos dois lados. */
@Serializable
enum class RefusalReason {
    /** O convidado recusou o convite. */
    INVITE_REFUSED,

    /** Saldo menor que a aposta (§14.6): recusa automática. */
    INSUFFICIENT_BALANCE,

    /** O convidado não estava mais ao alcance. */
    OUT_OF_RANGE,

    /** O anfitrião recusou o pedido de entrada. */
    HOST_REFUSED,

    /** Não há robô no lugar de quem entrar (ou as posições já travaram). */
    TABLE_FULL,
}

/** §16.2 texto em pt-BR do motivo (o mesmo nos dois lados). */
fun refusalMessage(reason: RefusalReason): String = when (reason) {
    RefusalReason.INVITE_REFUSED -> "Recusou o convite."
    RefusalReason.INSUFFICIENT_BALANCE -> "Saldo insuficiente."
    RefusalReason.OUT_OF_RANGE -> "Fora do alcance."
    RefusalReason.HOST_REFUSED -> "O anfitrião recusou."
    RefusalReason.TABLE_FULL -> "Mesa cheia."
}

/** Entrada ou mensagem recusada, com o motivo em pt-BR (ex.: [VERSION_MISMATCH_MESSAGE]) e, se for o caso, o de §16.2. */
@Serializable
@SerialName("rejected")
data class Rejected(val message: String, val reason: RefusalReason? = null) : Payload

/**
 * §16.2 convite (anfitrião → jogador próximo convidado, depois que ele se conecta): o anfitrião, os participantes
 * (humanos em [roster], robôs em [seating]) e as regras da mesa (vão uma única vez, aqui). Aberto por [timeoutMillis]: sem
 * resposta, é aceito sozinho.
 */
@Serializable
@SerialName("invite")
data class Invite(
    val hostId: Int,
    /** Id público do convidado nesta mesa. */
    val you: Int,
    val rules: TableRules,
    val roster: List<PlayerInfo>,
    val seating: SeatingState,
    val timeoutMillis: Long = INVITE_TIMEOUT_MILLIS,
) : Payload

/** §16.15 convite sem resposta até ser aceito automaticamente. */
const val INVITE_TIMEOUT_MILLIS: Long = 10_000

/** §16.15 espera depois de todos confirmarem as posições até o início do jogo. */
const val START_DELAY_MILLIS: Long = 2_000

/** §16.2 resposta ao convite: aceito, ou recusado com o [reason] (recusou ou saldo insuficiente). */
@Serializable
@SerialName("inviteReply")
data class InviteReply(val accepted: Boolean, val reason: RefusalReason? = null) : Payload

/** §16.2 rotaciona a mesa: troca com o próximo ocupante não confirmado na [direction]. */
@Serializable
@SerialName("rotate")
data class RotateSeat(val direction: RotateDirection) : Payload

/** §16.2 escolha de posições atualizada (troca, confirmação, entrada, saída), redistribuída a todos. */
@Serializable
@SerialName("seating")
data class SeatingUpdate(val seating: SeatingState) : Payload

/** §16.2 todos confirmaram: posições travadas; o jogo começa [startsInMillis] depois em todos os aparelhos. */
@Serializable
@SerialName("locked")
data class SeatsLocked(val seating: SeatingState, val startsInMillis: Long = START_DELAY_MILLIS) : Payload

/** Por que a mesa foi encerrada pelo anfitrião. */
@Serializable
enum class CloseReason {
    /** §16.2 o anfitrião saiu da escolha de posições: a mesa foi desfeita. */
    HOST_LEFT,

    /** §16.7 o anfitrião desligou o Wi-Fi do jogo: robôs assumem os humanos remotos e o jogo segue só no aparelho dele. */
    HOST_OFFLINE,
}

/** A mesa foi encerrada pelo anfitrião: não há o que reingressar nem assumir (§16.10 não se aplica). */
@Serializable
@SerialName("closed")
data class TableClosed(val reason: CloseReason) : Payload

/** Mesa antiga (sem escolha de posições, fase 2 remove): convidado troca para um assento **vago**. */
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

/** Convidado muda o nome (§16.2). */
@Serializable
@SerialName("rename")
data class Rename(val name: String) : Payload

/** Convidado sai da sala; durante o jogo vale como desistência (§16.9). */
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
data class GameStarted(
    val roster: List<PlayerInfo>,
    /** Personagem de cada assento de robô (índice do assento → id): todos os aparelhos mostram os mesmos robôs. */
    val robots: Map<Int, String> = emptyMap(),
) : Payload

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

/** §16.15 mensagem curta de teste do convidado (sem nada a enviar por 5 s), para medir a conexão; o anfitrião só confirma. */
@Serializable
@SerialName("ping")
data object Ping : Payload

/** Controle (fora da sequência): só confirma o recebido ([Envelope.ack]). */
@Serializable
@SerialName("ack")
data object AckOnly : Payload

/** Controle (fora da sequência): faltou a mensagem [from]; reenviar dela em diante (§16.5). */
@Serializable
@SerialName("resend")
data class ResendRequest(val from: Long) : Payload

/** §16.7 pedido de entrada recebido; aguardando a aprovação do anfitrião. */
@Serializable
@SerialName("joinWaiting")
data object JoinWaiting : Payload

/** Tipo de aviso público da mesa (§16.8: todos são avisados de cada ação do anfitrião). */
@Serializable
enum class NoticeKind {
    /** §16.8 o jogador caiu (o assento fica reservado a ele). */
    DISCONNECTED,

    /** §16.5 o jogador reingressou. */
    RECONNECTED,

    /** §16.9 o jogador desistiu: o assento fica "VAGO". */
    RESIGNED,

    /** §16.8 opção 2: um robô assumiu o assento. */
    REPLACED_BY_ROBOT,

    /** §16.8 opção 1: o anfitrião aguarda o jogador. */
    WAITING,

    /** §16.7, §16.8 opção 3: o assento está aberto a outro convidado ("aguardando recrutamento"). */
    SEAT_OPENED,

    /** §16.7 alguém entrou no meio do jogo (aprovado pelo anfitrião). */
    JOINED,

    /** §16.10 o anfitrião mudou ([TableNotice.playerId] é o novo anfitrião). */
    HOST_CHANGED,
}

/** Aviso público a todos (§16.8): [playerId] é o jogador a que se refere, [seat] o assento. Nada oculto. */
@Serializable
@SerialName("notice")
data class TableNotice(
    val kind: NoticeKind,
    val seat: Seat? = null,
    val playerId: Int? = null,
    /** §16.8 personagem do robô que assumiu ([NoticeKind.REPLACED_BY_ROBOT]); sempre um que não estava na mesa. */
    val persona: String? = null,
    /**
     * §16.2 na escolha de posições, por que o robô assumiu ([NoticeKind.REPLACED_BY_ROBOT]): recusa do convite, saldo,
     * fora do alcance; `null` = o jogador saiu (abandono).
     */
    val refusal: RefusalReason? = null,
) : Payload

/** Por que o jogo está pausado (§16.8, §16.9). */
@Serializable
enum class PauseReason {
    /** Chegou a vez de quem caiu. */
    DISCONNECTED,

    /** Chegou a vez de um assento "VAGO" (desistência). */
    VACANT,
}

/**
 * §16.8 o jogo está pausado na vez do [seat] até o anfitrião decidir (ou o jogador voltar). [hostChoice] é a ação já
 * escolhida pelo anfitrião ([NoticeKind.WAITING] ou [NoticeKind.SEAT_OPENED]); `null` enquanto ele não escolheu.
 */
@Serializable
@SerialName("paused")
data class Paused(val seat: Seat, val reason: PauseReason, val hostChoice: NoticeKind? = null) : Payload

/** §16.8 o jogo seguiu (o jogador voltou, um robô assumiu ou alguém entrou). */
@Serializable
@SerialName("resumed")
data object Resumed : Payload

/** §16.13 resultado em moedas deste jogador ao fim do jogo, calculado pelo anfitrião: [received] (já sem a aposta). */
@Serializable
@SerialName("payout")
data class Payout(val stake: Int, val received: Int) : Payload

/**
 * §16.10 **só para os sucessores**: o estado completo do jogo (a única exceção a §16.4), para assumir se o anfitrião cair
 * ou desistir. Vive só em memória no sucessor; nunca vai para disco, registro ou tela. [stateSeq] cresce a cada jogada.
 */
@Serializable
@SerialName("successorState")
data class SuccessorState(
    val stateSeq: Long,
    val match: Match,
    val events: List<PublicEvent>,
    val botRandomCalls: List<Long>,
    /** Todos os humanos da mesa com os tokens (o sucessor precisa deles para receber os reingressos, §16.4). */
    val members: List<MemberRecord>,
    val ledger: GroupBetLedger? = null,
    val nextPlayerId: Int,
    /** Personagens dos assentos de robô, por assento (§16.8 o primeiro não usado substitui). */
    val robotPersonas: Map<Int, String> = emptyMap(),
    /** Maior número de pedido já usado na rede: o novo anfitrião numera os seus depois dele. */
    val requestBase: Long = 0,
    /** §16.10 geração do anfitrião que enviou (0 = o original); quem assume anuncia a seguinte. */
    val epoch: Int = 0,
    /** §16.7, §16.10 assentos abertos a outro convidado (o novo anfitrião os herda). */
    val openSeats: List<Seat> = emptyList(),
    /** §16.8, §16.10 assento da pausa e a escolha já feita pelo anfitrião ([NoticeKind.WAITING]/[NoticeKind.SEAT_OPENED]). */
    val decisionSeat: Seat? = null,
    val decisionChoice: NoticeKind? = null,
) : Payload {
    override fun toString(): String = "SuccessorState(stateSeq=$stateSeq)" // nunca registrar cartas nem tokens
}

/** Um humano da mesa e o token dele (só no [SuccessorState]). */
@Serializable
data class MemberRecord(val info: PlayerInfo, val token: String) {
    override fun toString(): String = "MemberRecord(${info.id})"
}

/** §16.10 o sucessor confirma o recebimento do [SuccessorState] [stateSeq]. */
@Serializable
@SerialName("successorAck")
data class SuccessorAck(val stateSeq: Long) : Payload

/** §16.10 deixou de ser sucessor: descartar o estado completo. */
@Serializable
@SerialName("successorDrop")
data object SuccessorDrop : Payload

/** §16.10 o anfitrião passou o cargo a este jogador (que já tem o [SuccessorState]): assumir agora. */
@Serializable
@SerialName("becomeHost")
data object BecomeHost : Payload

/**
 * §16.7, §16.13 quanto debitar no celular de quem acaba de entrar no meio do jogo: a aposta da mesa no lugar de um robô;
 * 0 no lugar de quem saiu (herda a aposta retida).
 */
@Serializable
@SerialName("stakeDebit")
data class StakeDebit(val amount: Int) : Payload
