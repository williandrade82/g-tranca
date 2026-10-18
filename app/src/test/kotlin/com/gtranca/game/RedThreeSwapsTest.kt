package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §3.5, §6.5, §9.4 troca em lote: agrupamento dos 3 vermelhos do registro e repartição das reposições. */
class RedThreeSwapsTest {
    private fun c(text: String) = Card.parse(text)

    private fun laid(seat: Int, card: String, atTurnStart: Boolean = true, chained: Boolean = false) =
        RedThreeLaid(Seat(seat), c(card), atTurnStart = atTurnStart, chained = chained)

    private fun group(vararg entries: RedThreeLaid, mode: GameMode = GameMode.DUPLAS) =
        groupRedThreeLog(2, mode, entries.toList().withIndex().toList())

    @Test
    fun `§3_5 dois 3 vermelhos da mao no inicio da vez viram um aviso so`() {
        val notices = group(laid(1, "3H"), laid(1, "3D"))
        notices.size shouldBe 1
        notices.single().cards shouldBe listOf(c("3H"), c("3D"))
        notices.single().seat shouldBe Seat(1)
        notices.single().side shouldBe Side(1)
        notices.single().atTurnStart shouldBe true
        notices.single().chained shouldBe false
        notices.single().id shouldBe 2_000L
    }

    @Test
    fun `§6_5 a reposicao em cadeia continua sendo uma troca seguinte, depois do lote`() {
        val notices = group(laid(0, "3H"), laid(0, "3D"), laid(0, "3H", chained = true), laid(0, "3D", chained = true))
        notices.map { it.cards.size } shouldBe listOf(2, 1, 1)
        notices.map { it.chained } shouldBe listOf(false, true, true)
        notices.map { it.id } shouldBe listOf(2_000L, 2_002L, 2_003L)
    }

    @Test
    fun `§3_5 assentos diferentes e momentos diferentes nunca se fundem`() {
        group(laid(1, "3H"), laid(2, "3D")).map { it.seat.index } shouldBe listOf(1, 2)
        group(laid(1, "3H", atTurnStart = true), laid(1, "3D", atTurnStart = false)).map { it.atTurnStart } shouldBe listOf(true, false)
    }

    @Test
    fun `§9_4 os 3 vermelhos que vieram no morto do mesmo lado viram um aviso so`() {
        val notices = group(laid(2, "3H", atTurnStart = false), laid(2, "3D", atTurnStart = false))
        notices.size shouldBe 1
        notices.single().cards.size shouldBe 2
        notices.single().atTurnStart shouldBe false
    }

    @Test
    fun `§3_5 um 3 vermelho so continua um aviso de uma carta e sem registro nao ha avisos`() {
        group(laid(1, "3H")).single().cards shouldBe listOf(c("3H"))
        group() shouldBe emptyList()
    }

    private fun notice(id: Long, vararg cards: String, chained: Boolean = false) =
        RedThreeNotice(id, Side(0), Seat(0), cards.map(::c), atTurnStart = true, chained = chained)

    @Test
    fun `§6_5 lote de 2 sem cadeia - as duas reposicoes ficam na mao`() {
        val (given, kept) = allocateReplacements(listOf(notice(1, "3H", "3D")), listOf(c("QS"), c("7C")))
        given shouldBe emptyMap()
        kept shouldBe mapOf(1L to setOf(c("QS"), c("7C")))
    }

    @Test
    fun `§6_5 lote de 2 mais uma cadeia - o 3 vermelho em cadeia e a primeira reposicao do lote e a mao fica com as outras`() {
        // 3H e 3D baixados; a reposição do 3H foi outro 3 vermelho (cadeia, trocado depois), a do 3D e a da cadeia ficam na mão
        val swaps = listOf(notice(1, "3H", "3D"), notice(2, "3C", chained = true))
        val (given, kept) = allocateReplacements(swaps, listOf(c("QS"), c("7C")))
        given shouldBe mapOf(1L to listOf(2L))
        kept shouldBe mapOf(1L to setOf(c("QS")), 2L to setOf(c("7C")))
    }

    @Test
    fun `§6_5 lote de 2 mais duas cadeias - as duas reposicoes do lote sao as cadeias`() {
        val swaps = listOf(notice(1, "3H", "3D"), notice(2, "3C", chained = true), notice(3, "3S", chained = true))
        // cada troca em cadeia tem a sua reposição, que fica na mão (as duas últimas cartas)
        val (given, kept) = allocateReplacements(swaps, listOf(c("QS"), c("7C")))
        given shouldBe mapOf(1L to listOf(2L, 3L))
        kept shouldBe mapOf(2L to setOf(c("QS")), 3L to setOf(c("7C")))
    }

    @Test
    fun `§3_5 sem reposicoes (monte vazio) nada fica oculto`() {
        val (given, kept) = allocateReplacements(listOf(notice(1, "3H", "3D")), emptyList())
        given shouldBe emptyMap()
        kept shouldBe emptyMap()
    }
}
