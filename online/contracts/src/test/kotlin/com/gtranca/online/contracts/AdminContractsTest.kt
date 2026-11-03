package com.gtranca.online.contracts

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** §18.7: contratos de leitura do console de gerenciamento (admin-api -> lobby-service/economy-service). */
class AdminContractsTest {
    @Test
    fun `18_7 o cabecalho do segredo do console e diferente do segredo servico a servico`() {
        ADMIN_SECRET_HEADER shouldBe "X-Admin-Secret"
        (ADMIN_SECRET_HEADER == INTERNAL_SECRET_HEADER) shouldBe false
    }

    @Test
    fun `18_7 estatisticas do painel vao e voltam em JSON`() {
        val stats = AdminStatsDto(playersOnline = 3, tablesOpen = 2, tablesInGame = 1, matchesEndedLastHour = 5)
        Json.decodeFromString<AdminStatsDto>(Json.encodeToString(stats)) shouldBe stats
    }

    @Test
    fun `18_7 ficha da conta vai e volta em JSON, sem campos de senha ou token`() {
        val detail = AdminAccountDetail(
            id = "E_abc", kind = AccountKind.EMAIL, name = "Ana", email = "ana@exemplo.com", createdMs = 100L,
            lastSeenMs = 200L, blocked = false,
            currentTables = listOf(AdminTableSummary("ABC234", "Mesa Joaquina", TableState.ABERTA, 300L)),
        )
        val json = Json.encodeToString(detail)
        json shouldBe Json.encodeToString(Json.decodeFromString<AdminAccountDetail>(json))
        Json.decodeFromString<AdminAccountDetail>(json) shouldBe detail
    }

    @Test
    fun `18_7 carteira exposta ao console tem so saldo, inventario e migracao`() {
        val wallet = AdminWalletDto(coins = 1_000L, inventory = mapOf("b1" to 2), migratedLocal = true)
        Json.decodeFromString<AdminWalletDto>(Json.encodeToString(wallet)) shouldBe wallet
    }

    @Test
    fun `18_7 pedido de bloqueio e resposta de encerrar sessoes vao e voltam em JSON`() {
        val block = AdminBlockRequest(reason = "suspeita de fraude")
        Json.decodeFromString<AdminBlockRequest>(Json.encodeToString(block)) shouldBe block
        Json.decodeFromString<AdminBlockRequest>(Json.encodeToString(AdminBlockRequest())).reason shouldBe null

        val revoke = AdminRevokeSessionsResponse(revoked = 3)
        Json.decodeFromString<AdminRevokeSessionsResponse>(Json.encodeToString(revoke)) shouldBe revoke
    }

    @Test
    fun `18_7 os dois ErrorCode novos existem e sao distintos um do outro`() {
        ErrorCode.CONTA_BLOQUEADA shouldBe ErrorCode.valueOf("CONTA_BLOQUEADA")
        ErrorCode.SERVICO_INDISPONIVEL shouldBe ErrorCode.valueOf("SERVICO_INDISPONIVEL")
        (ErrorCode.CONTA_BLOQUEADA == ErrorCode.SERVICO_INDISPONIVEL) shouldBe false
    }

    @Test
    fun `18_7 lista de mesas do console reaproveita TableView, sem DTO de detalhe separado`() {
        val rules = TableRules(TableMode.DUPLAS, "{}", 1500, 50)
        val table = TableView(
            "ABC234", "g1", "Ana", TableState.EM_JOGO, rules,
            listOf(SeatView(0, SeatKind.HUMANO, "g1", "Ana")),
        )
        val page = AdminTablesPage(total = 1, tables = listOf(table))
        Json.decodeFromString<AdminTablesPage>(Json.encodeToString(page)) shouldBe page
    }

    @Test
    fun `18_7 pedido de encerrar mesa vai e volta em JSON, motivo opcional`() {
        val request = AdminCloseTableRequest(reason = "mesa travada ha 2 horas")
        Json.decodeFromString<AdminCloseTableRequest>(Json.encodeToString(request)) shouldBe request
        Json.decodeFromString<AdminCloseTableRequest>(Json.encodeToString(AdminCloseTableRequest())).reason shouldBe null
    }

    @Test
    fun `18_5 e 18_7 uma linha do livro-razao e a pagina vao e voltam em JSON`() {
        val entry = AdminLedgerEntryDto(
            reason = "AJUSTE", coins = 500L, boosterId = null, quantity = 0, reference = null,
            detail = "corrigindo um bug", key = "ajuste-op-1", createdMs = 100L,
        )
        val page = AdminLedgerPage(total = 1, entries = listOf(entry))
        Json.decodeFromString<AdminLedgerPage>(Json.encodeToString(page)) shouldBe page
    }

    @Test
    fun `18_7 pedido de ajuste exige motivo e chave de idempotencia, mas o ajuste em si e opcional no tipo`() {
        val request = AdminAdjustRequest(reason = "deu premio errado", operationId = "op-1", coinsDelta = -50, boosterId = "b1", quantityDelta = 2)
        Json.decodeFromString<AdminAdjustRequest>(Json.encodeToString(request)) shouldBe request
        // So os campos de ajuste sao opcionais no TIPO (a rota e quem exige pelo menos um, nao o DTO).
        val minimal = AdminAdjustRequest(reason = "motivo", operationId = "op-2")
        Json.decodeFromString<AdminAdjustRequest>(Json.encodeToString(minimal)) shouldBe minimal
    }

    @Test
    fun `18_6 e 18_7 uma migracao e a pagina vao e voltam em JSON`() {
        val migration = AdminMigrationDto(accountId = "E_abc", coins = 1_000L, createdMs = 100L)
        val page = AdminMigrationsPage(total = 1, migrations = listOf(migration))
        Json.decodeFromString<AdminMigrationsPage>(Json.encodeToString(page)) shouldBe page
    }
}
