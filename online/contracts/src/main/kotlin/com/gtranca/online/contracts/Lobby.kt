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

    /** Foto do jogador (JPEG pequeno em Base64), o mesmo teto da mesa local (§16.2). */
    const val MAX_PHOTO_BASE64 = 20_480
}

/** Modo da mesa (§14.4): individual = 2 lugares, duplas = 4. */
@Serializable
enum class TableMode(val seats: Int) { INDIVIDUAL(2), DUPLAS(4) }

/** Tempo de jogada (§18.4). */
@Serializable
enum class TempoDeJogada { INDETERMINADO, UM_MINUTO, CINCO_MINUTOS, QUINZE_MINUTOS }

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
    /** Nivel dos robos da mesa (§14.4, §14.5): Facil = Iniciante, Medio = Maduro, Dificil = Senior. */
    val robotLevel: BotLevel = BotLevel.FACIL,
)

@Serializable
data class SeatView(
    val index: Int,
    val kind: SeatKind,
    val guestId: String? = null,
    val name: String? = null,
    /**
     * Foto do humano (§18.2): caminho para baixa-la (`/api/lobby/guests/{id}/photo?v=N`); muda quando a foto muda, entao
     * serve de chave de cache. `null` = sem foto.
     */
    val photoUrl: String? = null,
    /** Robo: personagem e nivel (§14.5), conhecidos depois do inicio; antes, so o nivel da mesa. */
    val profile: BotProfile? = null,
    val level: BotLevel? = null,
)

/**
 * Uma mesa (§18.2). [hostId]/[hostName] vazios = mesa sem anfitriao (vazia, ou ninguem conectado pode assumir).
 * Assentos de duplas (§16.2): 0 e 2 = Dupla 1, 1 e 3 = Dupla 2.
 */
@Serializable
data class TableView(
    val id: String,
    val hostId: String,
    val hostName: String,
    val state: TableState,
    val rules: TableRules,
    val seats: List<SeatView>,
    val protocolVersion: Int = ONLINE_PROTOCOL_VERSION,
    /** Nome amigável sorteado pelo servidor (§18.2), o que o jogador vê; vazio em mesas antigas. */
    val name: String = "",
)

/** Mantido para a futura mesa criada pelo jogador; o servidor recusa nesta fase (§18.2). */
@Serializable
data class CreateTableRequest(val rules: TableRules)

/** GET /api/lobby/tables (§18.2): jogadores online no servidor e todas as mesas (abertas primeiro, depois em jogo). */
@Serializable
data class TableListView(
    val onlinePlayers: Int,
    val tables: List<TableView>,
)

/** A foto do jogador (§18.2): JPEG pequeno em Base64; `null` tira a foto. */
@Serializable
data class GuestPhoto(val photoBase64: String? = null)

/** Entregar a mesa (§18.2, §16.10): o anfitriao passa o cargo ao humano deste assento. */
@Serializable
data class PassHostRequest(val seat: Int)

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
    /** §18.2: saldo menor que a aposta da mesa ao sentar. */
    SALDO_INSUFICIENTE,
    LUGARES_VAGOS,
    LUGAR_INVALIDO,
    REQUISICAO_INVALIDA,
    CORPO_GRANDE_DEMAIS,
    LIMITE_DE_TAXA,

    /** A partida nao pode ser criada/alcancada agora (game-service fora do ar ou recusou). A mesa segue ABERTA. */
    PARTIDA_INDISPONIVEL,
    MENSAGEM_INVALIDA,

    /** O assento foi assumido por um robo (desistencia): nao ha como voltar a ele. */
    ASSENTO_ABANDONADO,

    /** Pedido sem assinatura valida do aplicativo (§18.2 "So o aplicativo oficial"). */
    APP_NAO_RECONHECIDO,

    /** Login Google desligado ou sem acesso ao Google/economia agora (so na rota de login, plano 2.6). */
    LOGIN_INDISPONIVEL,

    /** §18.1: e-mail ja cadastrado (cadastro por e-mail e senha). */
    EMAIL_JA_CADASTRADO,

    /** §18.1: muitas tentativas erradas de login com o mesmo e-mail; aguarde antes de tentar de novo. */
    LOGIN_BLOQUEADO,

    /** §18.7: a ficha de conta pedida pelo console de gerenciamento nao existe (nem como sessao, nem como conta de e-mail). */
    CONTA_INEXISTENTE,
}

@Serializable
data class ApiError(val code: ErrorCode, val message: String)
