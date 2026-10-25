package com.gtranca.net

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SeatingRoomTest {
    private val pool = listOf("bolt", "domino", "turbo", "zen")
    private fun h(id: Int) = SeatOccupant.Human(id)
    private fun r(p: String) = SeatOccupant.Robot(p)

    private fun room(vararg occupants: SeatOccupant, mode: GameMode = GameMode.DUPLAS) =
        SeatingRoom.of(SeatingState(mode, occupants.toList()), pool, seed = 7)

    @Test
    fun `§16_2 §14_5 sem assento vago - posicoes que sobram recebem robos sorteados entre os nao usados`() {
        val a = SeatingRoom.create(GameMode.DUPLAS, listOf(h(0), r("zen")), pool, seed = 3)
        val b = SeatingRoom.create(GameMode.DUPLAS, listOf(h(0), r("zen")), pool, seed = 3)
        a.state shouldBe b.state // mesma semente, mesmo sorteio
        a.state.occupants.take(2) shouldBe listOf(h(0), r("zen"))
        val robots = a.state.robots.values.toList()
        robots.size shouldBe 3
        robots.toSet().size shouldBe 3 // perfis diferentes entre si
        a.state.listed shouldBe true
    }

    @Test
    fun `§16_3 mesa individual tem 2 assentos`() {
        val s = SeatingRoom.create(GameMode.INDIVIDUAL, listOf(h(0)), pool, seed = 1).state
        s.occupants.size shouldBe 2
        s.occupants[1]::class shouldBe SeatOccupant.Robot::class
        // As duas direções levam ao único outro assento.
        s.rotationTarget(0, RotateDirection.LEFT) shouldBe Seat(1)
        s.rotationTarget(0, RotateDirection.RIGHT) shouldBe Seat(1)
    }

    @Test
    fun `§16_2 rotacao troca com o proximo ocupante nao confirmado, humano ou robo`() {
        val s = room(h(0), r("bolt"), h(1), r("zen"))
        s.rotate(0, RotateDirection.RIGHT)!!.state.occupants shouldBe listOf(r("bolt"), h(0), h(1), r("zen"))
        s.rotate(0, RotateDirection.LEFT)!!.state.occupants shouldBe listOf(r("zen"), r("bolt"), h(1), h(0))
        // Troca com humano: aparece para os dois (o estado é um só).
        s.rotate(1, RotateDirection.LEFT)!!.state.occupants shouldBe listOf(h(0), h(1), r("bolt"), r("zen"))
    }

    @Test
    fun `§16_2 rotacao pula quem ja confirmou e fica indisponivel sem ninguem para trocar`() {
        var s = room(h(0), h(1), h(2), r("zen"))
        s = s.confirm(1)!!
        // A direita de 0 está 1 (confirmado): pula para 2.
        s.state.rotationTarget(0, RotateDirection.RIGHT) shouldBe Seat(2)
        s = s.confirm(2)!!
        s.state.rotationTarget(0, RotateDirection.RIGHT) shouldBe Seat(3)
        s.state.rotationTarget(0, RotateDirection.LEFT) shouldBe Seat(3)
        // Individual com o outro confirmado: indisponível.
        val ind = room(h(0), h(1), mode = GameMode.INDIVIDUAL).confirm(1)!!
        ind.state.rotationTarget(0, RotateDirection.LEFT).shouldBeNull()
        ind.rotate(0, RotateDirection.RIGHT).shouldBeNull()
    }

    @Test
    fun `§16_2 confirmado nao e trocado por ninguem e nao rotaciona`() {
        val s = room(h(0), h(1), r("bolt"), r("zen")).confirm(1)!!
        s.rotate(1, RotateDirection.RIGHT).shouldBeNull()
        s.rotate(0, RotateDirection.RIGHT)!!.state.occupants[1] shouldBe h(1)
        s.confirm(1).shouldBeNull()
    }

    @Test
    fun `§16_2 todos os humanos confirmam - robos confirmam e as posicoes travam, mesa sai da lista`() {
        var s = room(h(0), r("bolt"), h(1), r("zen"))
        s = s.confirm(0)!!
        s.state.locked shouldBe false
        s.state.listed shouldBe true
        s = s.confirm(1)!!
        s.state.locked shouldBe true
        s.state.confirmed shouldBe setOf(0, 1, 2, 3)
        s.state.listed shouldBe false
        s.rotate(0, RotateDirection.RIGHT).shouldBeNull()
        s.state.robots shouldBe mapOf(1 to "bolt", 3 to "zen")
    }

    @Test
    fun `§16_2 §14_5 quem sai vira robo sorteado entre os nao usados - e o ultimo sem confirmar trava`() {
        var s = room(h(0), r("bolt"), h(1), r("zen")).confirm(0)!!
        s = s.replaceWithRobot(1)!!
        val persona = (s.state.occupants[2] as SeatOccupant.Robot).persona
        listOf("bolt", "zen") shouldNotContain persona
        s.state.locked shouldBe true // só restava ele sem confirmar
        // Determinístico pela semente.
        room(h(0), r("bolt"), h(1), r("zen")).confirm(0)!!.replaceWithRobot(1)!!.state shouldBe s.state
    }

    @Test
    fun `§16_2 abandono nos 2 s depois de travar - robo assume o lugar confirmado e a sala continua travada`() {
        val s = room(h(0), r("bolt"), h(1), r("zen")).confirm(0)!!.confirm(1)!!
        s.state.locked shouldBe true
        val after = s.replaceWithRobot(1)!!.state
        after.locked shouldBe true
        after.confirmed shouldBe setOf(0, 1, 2, 3)
        after.occupants[2].shouldBeInstanceOf<SeatOccupant.Robot>()
        after.humans shouldBe listOf(0)
    }

    @Test
    fun `§16_2 pedido aceito entra no lugar do primeiro robo pela ordem dos assentos - sem robo, mesa cheia`() {
        val s = room(h(0), h(1), r("bolt"), r("zen"))
        val after = s.admit(5).shouldNotBeNull()
        after.state.occupants shouldBe listOf(h(0), h(1), h(5), r("zen"))
        after.state.isConfirmed(Seat(2)) shouldBe false
        room(h(0), h(1), h(2), h(3)).admit(5).shouldBeNull()
        room(h(0), h(1), mode = GameMode.INDIVIDUAL).state.listed shouldBe false
    }
}
