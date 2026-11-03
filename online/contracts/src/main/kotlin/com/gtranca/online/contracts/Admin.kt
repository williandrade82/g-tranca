package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/**
 * §18.7: contratos de LEITURA entre o admin-api e os servicos donos dos dados (lobby-service, economy-service).
 *
 * O cabecalho usa um segredo PROPRIO ([ADMIN_SECRET_HEADER]), nunca o [INTERNAL_SECRET_HEADER] usado entre
 * lobby/game/economy: aquele tambem abre rotas de ESCRITA sensiveis (ex.: redefinir a senha de um jogador em
 * `/internal/lobby/accounts/reset-password`). Um admin-api comprometido com o segredo errado nunca deveria
 * conseguir mais do que consultar; por isso as rotas `/internal/admin/...` so aceitam [ADMIN_SECRET_HEADER], e as
 * rotas internas antigas continuam recusando esse cabecalho (cada uma so aceita o seu).
 *
 * Nao reusa [ONLINE_PROTOCOL_VERSION]: aquela versao e do protocolo app<->servidor (§18.8) e nao pode subir por um
 * motivo do console (um APK publicado nao tem nada a ver com o admin-web).
 */
const val ADMIN_SECRET_HEADER = "X-Admin-Secret"

/**
 * Versao dos contratos `/internal/admin/...` (este arquivo), §18.7. Cabecalho obrigatorio em toda chamada do
 * admin-api; versao ausente ou diferente e recusada com mensagem clara em pt-BR (como o protocolo app<->servidor,
 * §18.8), mas e um numero PROPRIO: nunca sobe por um motivo do console, nem [ONLINE_PROTOCOL_VERSION] por um
 * motivo deste arquivo.
 */
const val ADMIN_API_VERSION: Int = 1
const val ADMIN_API_VERSION_HEADER = "X-Admin-Api-Version"

/**
 * GET /internal/admin/stats (lobby-service). Numeros do Painel (§18.7, docs/telas-admin-web.md).
 * [matchesEndedLastHour] e contado em memoria a partir de agora; zera quando o servico reinicia (sem persistencia
 * ainda) e, por isso, fica menor que o real na primeira hora depois de um reinicio.
 */
@Serializable
data class AdminStatsDto(
    val playersOnline: Int,
    val tablesOpen: Int,
    val tablesInGame: Int,
    val matchesEndedLastHour: Int,
)

/**
 * Um item de GET /internal/admin/accounts ou a ficha de GET /internal/admin/accounts/{id} (lobby-service, §18.7).
 * Nunca inclui o token da sessao nem o hash da senha.
 *
 * [email]: so a conta EMAIL guarda e-mail; GOOGLE nunca guarda (so o id derivado do `sub`, por privacidade) e
 * CONVIDADO nao tem. [createdMs]: so EMAIL tem data de criacao real hoje; GOOGLE e CONVIDADO ficam `null` (o lobby
 * so conhece sessoes, que expiram por inatividade e nao sao "a conta nasceu em"; ver pendencia no relatorio do
 * `online-backend-dev`). [lastSeenMs]: ultimo uso de alguma sessao viva; `null` se nenhuma sessao esta viva agora.
 * [blocked]: §18.7 "Bloquear conta" (`definition.md`, decisao de 03/11/2026) - impede logar de novo e entrar em
 * mesa nova; nao ejeta de uma partida em andamento nem invalida tokens ja emitidos (isso e "Encerrar sessoes",
 * acao independente, ver [AdminRevokeSessionsResponse]). Vale para os tres tipos de conta, inclusive CONVIDADO
 * (protecao fraca para ele: o id nao e duradouro, ver pendencia no relatorio).
 */
@Serializable
data class AdminAccountSummary(
    val id: String,
    val kind: AccountKind,
    val name: String,
    val email: String? = null,
    val createdMs: Long? = null,
    val lastSeenMs: Long? = null,
    val blocked: Boolean = false,
)

/** GET /internal/admin/accounts: lista paginada (§18.7). [total] e o total SEM paginar, para o admin-web paginar. */
@Serializable
data class AdminAccountsPage(val total: Int, val accounts: List<AdminAccountSummary>)

/**
 * Mesa onde a conta aparece AGORA (ABERTA, EM_JOGO ou ENCERRADA ha pouco, §18.2): NAO e historico de partidas (uma
 * mesa ENCERRADA some do servidor poucos minutos depois). Ver pendencia no relatorio sobre historico de verdade.
 */
@Serializable
data class AdminTableSummary(val id: String, val name: String, val state: TableState, val updatedMs: Long)

/** GET /internal/admin/accounts/{id} (lobby-service, §18.7): a ficha da conta. */
@Serializable
data class AdminAccountDetail(
    val id: String,
    val kind: AccountKind,
    val name: String,
    val email: String? = null,
    val createdMs: Long? = null,
    val lastSeenMs: Long? = null,
    val blocked: Boolean = false,
    val currentTables: List<AdminTableSummary> = emptyList(),
)

/** GET /internal/admin/accounts/{id}/wallet (economy-service, §18.7): carteira e inventario, so leitura. */
@Serializable
data class AdminWalletDto(val coins: Long, val inventory: Map<String, Int>, val migratedLocal: Boolean)

/**
 * POST /internal/admin/accounts/{id}/block (lobby-service, §18.7). [reason] e so para o lobby registrar no log
 * (`lobby.admin` logger); a auditoria de verdade (quem, quando, o que, por que, sem se alterar) e do admin-api.
 * Opcional aqui: o admin-api pode exigir o motivo do lado dele antes de chamar esta rota.
 */
@Serializable
data class AdminBlockRequest(val reason: String? = null)

/**
 * POST /internal/admin/accounts/{id}/revoke-sessions (lobby-service, §18.7): "Encerrar sessões", acao
 * INDEPENDENTE do bloqueio. [revoked] e quantos tokens validos foram invalidados agora (0 = a conta nao tinha
 * sessao viva).
 */
@Serializable
data class AdminRevokeSessionsResponse(val revoked: Int)

/**
 * GET /internal/admin/tables?limit=&offset= (lobby-service, §18.7): lista paginada de mesas, em QUALQUER estado
 * (ao contrario de `GET /api/lobby/tables`, o endpoint do app, que nunca mostra mesa ENCERRADA). Reaproveita
 * [TableView] — ja tem id, anfitriao, estado, regras/criterios e assentos; o detalhe (`GET
 * /internal/admin/tables/{id}`) e o mesmo tipo, sem DTO separado. [total] e o total SEM paginar.
 */
@Serializable
data class AdminTablesPage(val total: Int, val tables: List<TableView>)

/**
 * POST /internal/admin/tables/{id}/close (lobby-service, §18.7) "Encerrar mesa travada": [reason] e so para o
 * lobby registrar no log; a auditoria de verdade e do admin-api. Idempotente (ENCERRADA fica como esta); mesa
 * EM_JOGO devolve [SERVICO_INDISPONIVEL] por ora (decisao de produto pendente sobre a aposta retida, ver relatorio
 * da etapa — `online-backend-dev`).
 */
@Serializable
data class AdminCloseTableRequest(val reason: String? = null)
