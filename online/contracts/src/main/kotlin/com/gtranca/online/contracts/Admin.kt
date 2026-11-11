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
 * Uma linha do livro-razao (economy-service, §18.5/§18.7): [reason] e o motivo fixo do lancamento (`SALDO_INICIAL`,
 * `COMPRA`, `APOSTA`, `AJUSTE`...), nunca o texto livre do operador (esse e [detail], so preenchido em `AJUSTE`).
 * [reference] e a referencia estruturada (mesa, oferta da loja...) quando existe. [key] e a chave de idempotencia
 * (util para o operador conferir se um ajuste duplicado foi de fato recusado).
 */
@Serializable
data class AdminLedgerEntryDto(
    val reason: String,
    val coins: Long,
    val boosterId: String? = null,
    val quantity: Int = 0,
    val reference: String? = null,
    val detail: String? = null,
    val key: String,
    val createdMs: Long,
)

/** GET /internal/admin/accounts/{id}/ledger?limit=&offset= (economy-service, §18.5/§18.7): mais recente primeiro. */
@Serializable
data class AdminLedgerPage(val total: Int, val entries: List<AdminLedgerEntryDto>)

/**
 * POST /internal/admin/accounts/{id}/adjust (economy-service, §18.7) "ajustar saldo ou booster": [reason] e
 * OBRIGATORIO (guardado no lancamento, motivo fixo `AJUSTE`). Pelo menos um de [coinsDelta] (pode ser negativo,
 * para debitar) ou o par [boosterId]/[quantityDelta] (tambem pode ser negativo) precisa vir preenchido e
 * diferente de zero. [operationId] e a chave de idempotencia: a MESMA chamada repetida (retry de rede do
 * admin-api) nunca ajusta duas vezes. Nunca deixa saldo nem quantidade negativos, mesmo sendo o operador — a
 * mesma regra de sempre (§14.6/§17.1).
 */
@Serializable
data class AdminAdjustRequest(
    val reason: String,
    val operationId: String,
    val coinsDelta: Long? = null,
    val boosterId: String? = null,
    val quantityDelta: Int? = null,
)

/**
 * Uma migracao de carteira local JA feita (economy-service, §18.6/§18.7): so a linha de MOEDAS de cada conta que
 * migrou (os boosters migrados na mesma operacao tem o mesmo motivo com outra chave; ver pendencia no relatorio
 * sobre mostra-los juntos ou separados). Para o operador revisar migracoes suspeitas (§18.6 "o operador pode
 * revisar migracoes").
 */
@Serializable
data class AdminMigrationDto(val accountId: String, val coins: Long, val createdMs: Long)

/** GET /internal/admin/migrations?limit=&offset= (economy-service, §18.6/§18.7): mais recente primeiro, TODAS as contas. */
@Serializable
data class AdminMigrationsPage(val total: Int, val migrations: List<AdminMigrationDto>)

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

// ------------------------------------------------------------------------------------------ Loja e configuracao (§18.7)

/**
 * De onde vem o valor ATUAL de um campo configuravel da loja/ganho diario (economy-service, §17.10/§14.6/§18.7):
 * [DEFAULT] = ninguem mudou ainda, vale a constante do `:core-engine` (`Shop.OFFERS`/`Coins.DAILY_GAIN`, nunca
 * duplicada aqui); [OVERRIDE] = um administrador mudou (ver `config_loja`, append-only, nunca `UPDATE`).
 */
@Serializable
enum class AdminConfigSource { DEFAULT, OVERRIDE }

/**
 * Um campo configuravel (preco/quantidade de uma oferta, ou o ganho diario): o valor ATUAL, de onde ele vem
 * ([AdminConfigSource]) e desde quando (`null` quando [source] e [AdminConfigSource.DEFAULT] — nunca mudou).
 */
@Serializable
data class AdminConfigFieldDto(val value: Long, val source: AdminConfigSource, val sinceMs: Long? = null)

/**
 * Uma das nove ofertas da loja (§17.10) com o preco e a quantidade ATUAIS (default ou com override, cada um
 * independente — mudar so o preco nao cria historico de quantidade). [boosterId] e fixo (vem do catalogo,
 * `:core-engine`, fora do alcance desta etapa); so [price] e [quantity] sao editaveis.
 */
@Serializable
data class AdminShopOfferConfigDto(val index: Int, val boosterId: String, val price: AdminConfigFieldDto, val quantity: AdminConfigFieldDto)

/**
 * GET /internal/admin/shop-config (economy-service, §17.10/§14.6/§18.7): o preco/quantidade atual de cada oferta
 * e o valor atual do ganho diario. O presente diario (§17.5) NAO esta nesta etapa: nao existe hoje como rota
 * online (so a carteira local tem o sorteio) nem tem um unico "valor" no `:core-engine` (e uma chance de 3 em 5
 * mais uma faixa min/max POR booster, que e parte do catalogo, explicitamente fora do alcance) — ver relatorio da
 * etapa.
 */
@Serializable
data class AdminShopConfigDto(val offers: List<AdminShopOfferConfigDto>, val dailyGain: AdminConfigFieldDto)

/**
 * Uma linha do historico de `config_loja` (economy-service, §18.7): [field] identifica o campo mudado
 * (`oferta-0-preco`, `oferta-0-quantidade`, `ganho-diario`...), nunca o motivo de negocio (esse e [reason], texto
 * livre do operador, sempre obrigatorio aqui, ao contrario do livro-razao onde so `AJUSTE` o usa).
 * [previousValue] e `null` so quando a linha e a PRIMEIRA mudanca desse campo (o valor anterior era o default do
 * `:core-engine`, nunca gravado aqui).
 */
@Serializable
data class AdminShopConfigHistoryEntryDto(
    val field: String,
    val value: Long,
    val previousValue: Long? = null,
    val reason: String,
    val operationId: String,
    val createdMs: Long,
)

/** GET /internal/admin/shop-config/history?limit=&offset= (economy-service, §18.7): mais recente primeiro. */
@Serializable
data class AdminShopConfigHistoryPage(val total: Int, val entries: List<AdminShopConfigHistoryEntryDto>)

/**
 * POST /internal/admin/shop-config/offer/{index} (economy-service, §17.10/§18.7): muda o preco e/ou a quantidade
 * da oferta [index]. Pelo menos um dos dois precisa vir preenchido; [reason] e [operationId] sao sempre
 * obrigatorios (idempotencia, como as demais mutacoes do servico).
 */
@Serializable
data class AdminShopOfferAdjustRequest(
    val price: Int? = null,
    val quantity: Int? = null,
    val reason: String,
    val operationId: String,
)

/**
 * POST /internal/admin/shop-config/daily-gain (economy-service, §14.6/§18.7): novo valor do ganho diario
 * (`Coins.DAILY_GAIN`). [reason] e [operationId] obrigatorios.
 */
@Serializable
data class AdminShopGainAdjustRequest(val amount: Long, val reason: String, val operationId: String)
