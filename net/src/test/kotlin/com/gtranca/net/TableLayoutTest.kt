package com.gtranca.net

import com.gtranca.engine.model.GameMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TableLayoutTest {
    private val pool = listOf("bolt", "domino", "turbo", "zen")
    private fun duplas() = TableLayout.initial(GameMode.DUPLAS)

    @Test
    fun `§16_2 §16_3 mesa nova - o recrutador no lugar 0 e os demais VAGO, 2 lugares no individual e 4 em duplas`() {
        duplas().slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Vacant, Slot.Vacant)
        TableLayout.initial(GameMode.INDIVIDUAL).slots shouldBe listOf(Slot.Host, Slot.Vacant)
        duplas().canStart shouldBe false
    }

    @Test
    fun `§16_2 o rotulo do convite - Parceiro so o da frente em duplas, Adversario nos outros e no individual`() {
        val d = duplas()
        d.relationOf(0) shouldBe SeatRelation.SELF
        d.relationOf(2) shouldBe SeatRelation.PARTNER
        d.relationOf(1) shouldBe SeatRelation.OPPONENT
        d.relationOf(3) shouldBe SeatRelation.OPPONENT
        TableLayout.initial(GameMode.INDIVIDUAL).relationOf(1) shouldBe SeatRelation.OPPONENT
    }

    @Test
    fun `§16_2 chamar so em lugar VAGO, e cada jogador ocupa um lugar so`() {
        val l = duplas().call(2, "kb", "Bia").shouldNotBeNull()
        l.slots[2] shouldBe Slot.Called("kb", "Bia")
        l.hasCalled shouldBe true
        l.call(2, "kc", "Caio").shouldBeNull() // ocupado
        l.call(1, "kb", "Bia").shouldBeNull() // já está na mesa
        l.call(0, "kc", "Caio").shouldBeNull() // lugar do recrutador
        l.call(9, "kc", "Caio").shouldBeNull()
    }

    @Test
    fun `§14_5 §16_2 robo so em lugar VAGO e cada personagem uma vez`() {
        val l = duplas().placeRobot(1, "bolt").shouldNotBeNull()
        l.placeRobot(2, "bolt").shouldBeNull()
        l.placeRobot(1, "zen").shouldBeNull()
        l.placeRobot(2, "zen")!!.robots shouldBe mapOf(1 to "bolt", 2 to "zen")
    }

    @Test
    fun `§16_2 remover - robo volta, Chamado some, nunca o lugar 0 nem um VAGO`() {
        val l = duplas().placeRobot(1, "bolt")!!.call(2, "kb", "Bia")!!
        l.remove(1)!!.slots[1] shouldBe Slot.Vacant
        l.remove(2)!!.slots[2] shouldBe Slot.Vacant
        l.remove(0).shouldBeNull()
        l.remove(3).shouldBeNull()
    }

    @Test
    fun `§16_2 mover - ocupado troca, VAGO move, o recrutador nao se move e pode mover um Chamado`() {
        val l = duplas().placeRobot(1, "bolt")!!.call(2, "kb", "Bia")!!
        l.move(1, 2)!!.slots.drop(1).take(2) shouldBe listOf(Slot.Called("kb", "Bia"), Slot.Robot("bolt")) // troca
        l.move(2, 3)!!.slots shouldBe listOf(Slot.Host, Slot.Robot("bolt"), Slot.Vacant, Slot.Called("kb", "Bia")) // move
        l.move(0, 1).shouldBeNull()
        l.move(1, 0).shouldBeNull()
        l.move(3, 1).shouldBeNull() // origem VAGO
        l.move(1, 1).shouldBeNull()
    }

    @Test
    fun `§16_2 aceitar vira Recrutado, recusar ou sair devolve o lugar a VAGO sem robo sorteado`() {
        val l = duplas().call(1, "kb", "Bia")!!
        val accepted = l.accept("kb")!!
        accepted.slots[1] shouldBe Slot.Recruited("kb", "Bia")
        accepted.accept("kb").shouldBeNull() // já aceitou
        l.accept("kx").shouldBeNull()
        accepted.vacate("kb")!!.slots[1] shouldBe Slot.Vacant
        l.vacate("kb")!!.slots[1] shouldBe Slot.Vacant
        l.vacate("kx").shouldBeNull()
    }

    @Test
    fun `§14_5 §16_2 completar com robos sorteia so os nao usados, e e deterministico pela semente`() {
        val base = duplas().placeRobot(3, "zen")!!.call(1, "kb", "Bia")!!.accept("kb")!!
        val a = base.fillWithRobots(pool, Random(7))
        val b = base.fillWithRobots(pool, Random(7))
        a shouldBe b
        a.slots[2].shouldNotBeNull()
        (a.slots[2] as Slot.Robot).persona.let { it in listOf("bolt", "domino", "turbo") } shouldBe true
        a.slots[1] shouldBe Slot.Recruited("kb", "Bia") // não mexe em humanos
        a.slots[3] shouldBe Slot.Robot("zen") // nem em robôs
        a.isFull shouldBe true
        a.canStart shouldBe true
        // Mesa só de robôs: os 3 lugares, todos diferentes.
        duplas().fillWithRobots(pool, Random(1)).robots.values.toSet().size shouldBe 3
    }

    @Test
    fun `§16_2 Comecar Partida so com todos os lugares preenchidos e nenhum Chamado`() {
        val l = duplas().placeRobot(1, "bolt")!!.placeRobot(2, "zen")!!
        l.canStart shouldBe false // falta o lugar 3
        val withCalled = l.call(3, "kb", "Bia")!!
        withCalled.isFull shouldBe true
        withCalled.canStart shouldBe false // Chamado pendente
        withCalled.accept("kb")!!.canStart shouldBe true
        TableLayout.initial(GameMode.INDIVIDUAL).placeRobot(1, "turbo")!!.canStart shouldBe true
    }

    @Test
    fun `§16_2 os lugares viajam como estao - ida e volta pelo protocolo`() {
        val l = duplas().placeRobot(1, "bolt")!!.call(2, "kb", "Bia")!!.accept("kb")!!
        val json = ProtocolCodec.json.encodeToString(TableLayout.serializer(), l)
        ProtocolCodec.json.decodeFromString(TableLayout.serializer(), json) shouldBe l
    }
}
