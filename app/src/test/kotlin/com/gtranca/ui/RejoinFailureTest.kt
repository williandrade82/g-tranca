package com.gtranca.ui

import com.gtranca.R
import com.gtranca.multiplayer.MpEnd
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §16.5, §16.12 as mensagens de "Voltar à mesa": mesa não encontrada, vaga preenchida e mesa encerrada. */
class RejoinFailureTest {
    @Test
    fun `cada fim de Voltar a mesa tem a sua mensagem`() {
        rejoinFailure(MpEnd.NotFound) shouldBe RejoinFailure(R.string.rejoin_not_found)
        rejoinFailure(MpEnd.Closed) shouldBe RejoinFailure(R.string.rejoin_closed)
        rejoinFailure(MpEnd.Ended(com.gtranca.net.CloseReason.HOST_LEFT)) shouldBe RejoinFailure(R.string.rejoin_closed)
        // o token recusado pelo anfitrião: a vaga foi ocupada
        rejoinFailure(MpEnd.Refused("Não foi possível voltar à mesa: o lugar já foi ocupado.")) shouldBe RejoinFailure(R.string.rejoin_taken)
        // outra versão do jogo traz a própria mensagem
        rejoinFailure(MpEnd.Refused("O anfitrião está com outra versão do jogo")) shouldBe RejoinFailure(null, "O anfitrião está com outra versão do jogo")
    }
}
