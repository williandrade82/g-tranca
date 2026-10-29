package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/** Cabecalho HTTP com a versao do protocolo (§18.8). */
const val PROTOCOL_HEADER = "X-Protocol-Version"

/** Limites de validacao compartilhados por app e servidor. */
object LobbyLimits {
    const val MAX_NAME = 24
    const val MAX_RULES_JSON = 8_000
    const val MAX_TARGET_SCORE = 100_000
    const val MAX_BET = 1_000_000
    const val MAX_BODY_BYTES = 32 * 1024
}

/** Modo da mesa (§14.4): individual = 2 lugares, duplas = 4. */
@Serializable
enum class TableMode(val seats: Int) { INDIVIDUAL(2), DUPLAS(4) }

/** Tempo de jogada (§18.4). */
@Serializable
enum class TempoDeJogada { INDETERMINADO, UM_MINUTO, CINCO_MINUTOS }

/** Punicao de abandono (§18.4). */
@Serializable
enum class Punicao { SEM_PUNICAO, PERDE_APOSTA }

/** Visibilidade (§18.2): so PRIVADA e aceita nesta versao. */
@Serializable
enum class Visibilidade { PRIVADA, PUBLICA }

@Serializable
enum class TableState { ABERTA, EM_JOGO, ENCERRADA }

@Serializable
enum class SeatKind { VAGO, HUMANO, ROBO }

/** Convidado anonimo: id e token opaco (o token so aparece nesta resposta). */
@Serializable
data class GuestIdentity(val id: String, val name: String, val token: String)

@Serializable
data class CreateGuestRequest(val name: String)

/**
 * Regras e criterios da mesa (§18.2, §18.4); imutaveis depois de iniciar.
 * [rulesJson] e opaco: o lobby so limita o tamanho; o game-service valida com o motor.
 */
@Serializable
data class TableRules(
    val mode: TableMode,
    val rulesJson: String,
    val targetScore: Int = 3000,
    val bet: Int = 0,
    val skillBoostersAllowed: Boolean = true,
    val tempoDeJogada: TempoDeJogada = TempoDeJogada.INDETERMINADO,
    val punicao: Punicao = Punicao.SEM_PUNICAO,
    val visibilidade: Visibilidade = Visibilidade.PRIVADA,
)

@Serializable
data class SeatView(
    val index: Int,
    val kind: SeatKind,
    val guestId: String? = null,
    val name: String? = null,
)

@Serializable
data class TableView(
    val id: String,
    val hostId: String,
    val hostName: String,
    val state: TableState,
    val rules: TableRules,
    val seats: List<SeatView>,
    val protocolVersion: Int = ONLINE_PROTOCOL_VERSION,
)

@Serializable
data class CreateTableRequest(val rules: TableRules)

/** [seat] opcional: sem ele, ocupa o primeiro lugar vago. */
@Serializable
data class JoinRequest(val seat: Int? = null)

@Serializable
enum class ErrorCode {
    MESA_INEXISTENTE,
    MESA_CHEIA,
    MESA_JA_INICIADA,
    VERSAO_INCOMPATIVEL,
    SEM_PERMISSAO,
    NAO_AUTENTICADO,
    /** Declarado; o lobby ainda nao consulta o saldo (sem economy-service). */
    SALDO_INSUFICIENTE,
    LUGARES_VAGOS,
    LUGAR_INVALIDO,
    REQUISICAO_INVALIDA,
    CORPO_GRANDE_DEMAIS,
    LIMITE_DE_TAXA,
}

@Serializable
data class ApiError(val code: ErrorCode, val message: String)
