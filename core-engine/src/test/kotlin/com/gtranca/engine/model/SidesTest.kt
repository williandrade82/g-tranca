package com.gtranca.engine.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SidesTest {

    @Test
    fun `modo individual tem 2 assentos e cada jogador e um lado`() {
        // §1.1 individual: 2 jogadores, cada jogador é um lado
        GameMode.INDIVIDUAL.seatCount shouldBe 2
        GameMode.INDIVIDUAL.sides shouldContainExactly listOf(Side(0), Side(1))
        GameMode.INDIVIDUAL.sideOf(Seat(0)) shouldBe Side(0)
        GameMode.INDIVIDUAL.sideOf(Seat(1)) shouldBe Side(1)
        GameMode.INDIVIDUAL.seatsOf(Side(1)) shouldContainExactly listOf(Seat(1))
    }

    @Test
    fun `modo duplas tem 4 assentos e parceiros em posicoes opostas`() {
        // §1.1 duplas: 4 jogadores, cada dupla é um lado; parceiros em posições opostas
        GameMode.DUPLAS.seatCount shouldBe 4
        GameMode.DUPLAS.sides shouldContainExactly listOf(Side(0), Side(1))
        GameMode.DUPLAS.seatsOf(Side(0)) shouldContainExactly listOf(Seat(0), Seat(2))
        GameMode.DUPLAS.seatsOf(Side(1)) shouldContainExactly listOf(Seat(1), Seat(3))
        GameMode.DUPLAS.sideOf(Seat(3)) shouldBe Side(1)
    }

    @Test
    fun `ordem horaria e indice crescente e volta ao inicio`() {
        // §4.2 sentido horário (convenção: índice crescente)
        GameMode.DUPLAS.nextSeat(Seat(0)) shouldBe Seat(1)
        GameMode.DUPLAS.nextSeat(Seat(3)) shouldBe Seat(0)
        GameMode.INDIVIDUAL.nextSeat(Seat(1)) shouldBe Seat(0)
        GameMode.DUPLAS.seatsInPlayOrder(Seat(2)) shouldContainExactly listOf(Seat(2), Seat(3), Seat(0), Seat(1))
    }

    @Test
    fun `assento fora do modo e rejeitado`() {
        shouldThrow<IllegalArgumentException> { GameMode.INDIVIDUAL.sideOf(Seat(2)) }
        shouldThrow<IllegalArgumentException> { Seat(-1) }
    }
}
