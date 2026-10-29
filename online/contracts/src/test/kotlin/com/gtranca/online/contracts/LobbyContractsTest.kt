package com.gtranca.online.contracts

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class LobbyContractsTest {
    @Test
    fun `18_2 modo define 2 ou 4 lugares`() {
        TableMode.INDIVIDUAL.seats shouldBe 2
        TableMode.DUPLAS.seats shouldBe 4
    }

    @Test
    fun `18_2 e 18_4 regras da mesa vao e voltam em JSON`() {
        val rules = TableRules(
            TableMode.DUPLAS, "{}", 3000, 50, false,
            TempoDeJogada.CINCO_MINUTOS, Punicao.PERDE_APOSTA,
        )
        val view = TableView("ABC234", "g1", "Ana", TableState.ABERTA, rules, listOf(SeatView(0, SeatKind.HUMANO, "g1", "Ana")))
        Json.decodeFromString<TableView>(Json.encodeToString(view)) shouldBe view
        view.rules.visibilidade shouldBe Visibilidade.PRIVADA
        view.protocolVersion shouldBe ONLINE_PROTOCOL_VERSION
    }
}
