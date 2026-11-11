package com.gtranca.ui.online

import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.ErrorCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Mapeamento de [ErrorCode] do servidor para [OnlineProblem]/[OnlineAccountProblem] (docs/telas.md 3.21 a 3.24).
 * §18.7: conta bloqueada pelo operador do console e o 503 genérico novos no enum compartilhado.
 */
class OnlineProblemTest {

    // §18.7 bloquear só impede login/entrar numa mesa nova; a tela mostra um texto específico, não o genérico
    @Test
    fun `conta bloqueada vira problema especifico`() {
        val e = OnlineException(OnlineErrorKind.API, ErrorCode.CONTA_BLOQUEADA, 403)
        e.toProblem() shouldBe OnlineProblem.ACCOUNT_BLOCKED
    }

    // §18.7 503 genérico do admin-api (ou de qualquer serviço) reaproveita o texto de manutenção
    @Test
    fun `servico indisponivel vira manutencao`() {
        val e = OnlineException(OnlineErrorKind.API, ErrorCode.SERVICO_INDISPONIVEL, 503)
        e.toProblem() shouldBe OnlineProblem.MAINTENANCE
    }

    // §18.7 na tela de Conta (3.22), bloqueada não é a mesma coisa que senha errada
    @Test
    fun `conta bloqueada no login nao e credencial invalida`() {
        val e = OnlineException(OnlineErrorKind.API, ErrorCode.CONTA_BLOQUEADA, 403)
        e.toAccountProblem(signup = false) shouldBe OnlineAccountProblem.ACCOUNT_BLOCKED
    }
}
