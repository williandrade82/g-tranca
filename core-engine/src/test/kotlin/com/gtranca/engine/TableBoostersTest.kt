package com.gtranca.engine

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** §17.4 os boosters ficam no assento da mesa (e vão com o jogo salvo e com o anfitrião), não em quem senta. */
class TableBoostersTest {
    @Test
    fun `§17_4 assento sem escolha nao tem boosters nem reacoes`() {
        val table = TableBoosters()
        table.of(2) shouldBe SeatBoosters()
        table.of(2).confirmed shouldBe false
        table.of(2).reactionsLeft shouldBe 0
    }

    @Test
    fun `§17_4 confirmar guarda os boosters no assento, ate 3 e sem repetir`() {
        val table = TableBoosters().confirm(1, listOf("b1", "b1", "b2", "b3", "bx"))
        table.of(1).boosters shouldBe listOf("b1", "b2", "b3")
        table.of(1).confirmed shouldBe true
        table.of(0) shouldBe SeatBoosters() // os outros assentos não mudam
    }

    @Test
    fun `§17_8 as reacoes sao do assento - 3 usos e o quarto e recusado`() {
        var table = TableBoosters.single(0, listOf("b3"))
        table.of(0).reactionsLeft shouldBe 3
        repeat(3) { table = table.useReaction(0)!! }
        table.of(0).reactionsLeft shouldBe 0
        table.useReaction(0) shouldBe null
    }

    @Test
    fun `§17_8 sem o b3 no assento nao ha reacao`() {
        TableBoosters.single(0, listOf("b1")).useReaction(0) shouldBe null
        TableBoosters().useReaction(3) shouldBe null
    }

    @Test
    fun `§17_4 quem assume o assento segue com os boosters e os usos que sobraram`() {
        // o ocupante A usou 2 reações; ao sair, o assento guarda 1; o ocupante B herda o estado do assento
        var table = TableBoosters.single(2, listOf("b1", "b3"))
        repeat(2) { table = table.useReaction(2)!! }
        val inherited = table.of(2)
        inherited.boosters shouldBe listOf("b1", "b3")
        inherited.reactionsLeft shouldBe 1
        inherited.confirmed shouldBe true
    }

    @Test
    fun `§17_3 partida nova zera os assentos e todos escolhem de novo`() {
        val table = TableBoosters.single(0, listOf("b1")).confirm(2, listOf("b3")).newRound()
        table.of(0).confirmed shouldBe false
        table.of(2).boosters shouldBe emptyList()
    }

    @Test
    fun `§17_4 vai e volta pelo JSON do jogo salvo ou do anfitriao`() {
        var table = TableBoosters.single(0, listOf("b3", "b2")).confirm(3, listOf("b1"))
        table = table.useReaction(0)!!
        val json = Json { encodeDefaults = true }
        json.decodeFromString(TableBoosters.serializer(), json.encodeToString(TableBoosters.serializer(), table)) shouldBe table
    }
}
