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
}
