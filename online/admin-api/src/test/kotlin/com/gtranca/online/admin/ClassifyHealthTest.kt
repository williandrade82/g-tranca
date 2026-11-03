package com.gtranca.online.admin

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Sec18.7 Painel: a cor de cada servico, sem rede (funcao pura usada por HttpHealthGateway). */
class ClassifyHealthTest {
    @Test
    fun `18_7 200 rapido e verde`() {
        classifyHealth(httpStatus = 200, bodyUp = true, latencyMs = 10, yellowAboveMs = 500) shouldBe ServiceStatusLevel.VERDE
    }

    @Test
    fun `18_7 200 no limite exato do limiar ainda e verde, passou do limiar e amarelo`() {
        classifyHealth(httpStatus = 200, bodyUp = true, latencyMs = 500, yellowAboveMs = 500) shouldBe ServiceStatusLevel.VERDE
        classifyHealth(httpStatus = 200, bodyUp = true, latencyMs = 501, yellowAboveMs = 500) shouldBe ServiceStatusLevel.AMARELO
    }

    @Test
    fun `18_7 200 com corpo diferente de UP e amarelo, mesmo rapido`() {
        classifyHealth(httpStatus = 200, bodyUp = false, latencyMs = 5, yellowAboveMs = 500) shouldBe ServiceStatusLevel.AMARELO
    }

    @Test
    fun `18_7 status http diferente de 200 e vermelho, mesmo rapido e com corpo UP`() {
        classifyHealth(httpStatus = 503, bodyUp = true, latencyMs = 5, yellowAboveMs = 500) shouldBe ServiceStatusLevel.VERMELHO
    }
}
