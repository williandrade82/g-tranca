package com.gtranca.online.economy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Postgres indisponivel no arranque: o servico espera e tenta de novo, sem sair. */
class DatabaseRetryTest {
    @Test
    fun `tenta de novo com espera crescente limitada ate o banco responder`() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val r = Database.retryForever({ sleeps += it }, maxDelayMs = 4_000L) {
            if (++calls < 5) error("sem banco") else "ok"
        }
        r shouldBe "ok"
        sleeps shouldBe listOf(1_000L, 2_000L, 4_000L, 4_000L)
    }

    @Test
    fun `pool criado com o banco fora do ar nao lanca excecao`() {
        Database.connect("jdbc:postgresql://127.0.0.1:1/nada", "u", "p").close()
    }
}
