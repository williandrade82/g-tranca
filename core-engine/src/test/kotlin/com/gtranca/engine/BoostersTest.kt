package com.gtranca.engine

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/** §17 boosters: catálogo, inventário, escolha (seletor) e presente diário. */
class BoostersTest {
    private val catalog = BoosterCatalog.DEFAULT

    @Test
    fun `§17_1 catalogo inicial - b1 b2 b3 com custo 1, 5 iniciais e faixas de sorteio`() {
        catalog.map { it.id } shouldBe listOf("b1", "b2", "b3")
        catalog.map { it.name } shouldBe listOf("Green-LED", "Red-LED", "Reações")
        catalog.all { it.cost == 1 && it.initialQuantity == 5 } shouldBe true
        catalog.map { it.giftMin to it.giftMax } shouldBe listOf(1 to 3, 1 to 3, 2 to 7)
    }

    @Test
    fun `§17_1 quem nao tem inventario recebe a quantidade inicial e as moedas ficam fora`() {
        val inv = Inventory.initial(catalog)
        catalog.forEach { inv.quantity(it.id) shouldBe 5 }
        inv.quantity("coins") shouldBe 0
    }

    @Test
    fun `§17_1 inventario nunca fica negativo - consumir mais do que ha falha`() {
        val inv = Inventory(mapOf("b1" to 1))
        inv.consume("b1", 1)!!.quantity("b1") shouldBe 0
        inv.consume("b1", 2) shouldBe null
        inv.consume("b2", 1) shouldBe null
        assertThrows<IllegalArgumentException> { Inventory(mapOf("b1" to -1)) }
    }

    @Test
    fun `§17_2 marca ate 3 boosters distintos, a quarta e recusada e desmarcar libera`() {
        val cat4 = catalog + BoosterDef("bx", "X", "x", 1, 5, 1, 1)
        val big = Inventory(mapOf("b1" to 5, "b2" to 5, "b3" to 5, "bx" to 5))
        var sel = emptyList<String>()
        sel = Loadout.toggle(sel, "b1", big, cat4)
        sel = Loadout.toggle(sel, "b2", big, cat4)
        sel = Loadout.toggle(sel, "b3", big, cat4)
        sel shouldContainExactly listOf("b1", "b2", "b3")
        Loadout.toggle(sel, "bx", big, cat4) shouldBe sel // a quarta não entra
        Loadout.toggle(sel, "b2", big, cat4) shouldContainExactly listOf("b1", "b3")
    }

    @Test
    fun `§17_2 moedas e ids fora do catalogo nao podem ser marcados`() {
        val inv = Inventory(mapOf("b1" to 5, "coins" to 900))
        Loadout.toggle(emptyList(), "coins", inv, catalog) shouldBe emptyList()
        Loadout.normalize(listOf("coins", "b1", "b1"), inv, catalog) shouldContainExactly listOf("b1")
    }

    @Test
    fun `§17_2 quantidade 0 nao marca e sai da marcacao`() {
        val inv = Inventory(mapOf("b1" to 0, "b2" to 2))
        Loadout.toggle(emptyList(), "b1", inv, catalog) shouldBe emptyList()
        Loadout.normalize(listOf("b1", "b2"), inv, catalog) shouldContainExactly listOf("b2")
    }

    @Test
    fun `§17_3 Seguir debita o custo de cada escolhido e so deles`() {
        val inv = Inventory(mapOf("b1" to 5, "b2" to 5, "b3" to 5))
        val done = Loadout.confirm(listOf("b1", "b3"), inv, catalog)
        done.boosters shouldContainExactly listOf("b1", "b3")
        done.inventory.quantities shouldBe mapOf("b1" to 4, "b2" to 5, "b3" to 4)
    }

    @Test
    fun `§17_3 sem nenhum selecionado a partida segue sem boosters e nada e debitado`() {
        val inv = Inventory.initial(catalog)
        val done = Loadout.confirm(emptyList(), inv, catalog)
        done.boosters shouldBe emptyList()
        done.inventory shouldBe inv
    }

    @Test
    fun `§17_3 o que nao puder ser debitado fica de fora`() {
        val inv = Inventory(mapOf("b1" to 0, "b2" to 1))
        val done = Loadout.confirm(listOf("b1", "b2"), inv, catalog)
        done.boosters shouldContainExactly listOf("b2")
        done.inventory.quantity("b2") shouldBe 0
        // custo maior que 1: só entra se a quantidade cobre
        val pricey = listOf(BoosterDef("bp", "P", "p", 2, 5, 1, 1))
        Loadout.confirm(listOf("bp"), Inventory(mapOf("bp" to 1)), pricey).boosters shouldBe emptyList()
        Loadout.confirm(listOf("bp"), Inventory(mapOf("bp" to 3)), pricey).inventory.quantity("bp") shouldBe 1
    }

    @Test
    fun `§17_5 so ha sorteio num dia depois do ultimo, relogio atrasado nao sorteia de novo`() {
        DailyGift.isDue(100, null) shouldBe true
        DailyGift.isDue(101, 100) shouldBe true
        DailyGift.isDue(100, 100) shouldBe false
        DailyGift.isDue(99, 100) shouldBe false
    }

    @Test
    fun `§17_5 mesma semente, mesmo sorteio`() {
        val inv = Inventory.initial(catalog)
        DailyGift.draw(inv, catalog, Random(7)) shouldBe DailyGift.draw(inv, catalog, Random(7))
    }

    @Test
    fun `§17_5 a chance e de cerca de 3 em 5 e a quantidade fica na faixa do catalogo`() {
        val inv = Inventory(catalog.associate { it.id to 0 })
        val rng = Random(42)
        val gifts = (1..5000).map { DailyGift.draw(inv, catalog, rng) }
        val rate = gifts.count { it != null } / 5000.0
        (rate in 0.57..0.63) shouldBe true
        gifts.filterNotNull().forEach {
            val def = catalog.first { d -> d.id == it.boosterId }
            it.quantity shouldBeInRange def.giftMin..def.giftMax
        }
    }

    @Test
    fun `§17_5 quem tem 10 ou mais de um booster nao o recebe e o total nunca passa de 10`() {
        runBlocking {
            checkAll(Arb.int(0..12), Arb.int(0..12), Arb.int(0..12), Arb.int()) { a, b, c, seed ->
                val inv = Inventory(mapOf("b1" to a, "b2" to b, "b3" to c))
                val gift = DailyGift.draw(inv, catalog, Random(seed))
                if (gift != null) {
                    inv.quantity(gift.boosterId) shouldBeLessThanOrEqual 9
                    (inv.quantity(gift.boosterId) + gift.quantity) shouldBeLessThanOrEqual 10
                    gift.quantity shouldBeInRange 1..7
                }
            }
        }
    }

    @Test
    fun `§17_5 quem ja tem 10 de todos nao recebe nada`() {
        val full = Inventory(catalog.associate { it.id to 10 })
        repeat(200) { DailyGift.draw(full, catalog, Random(it)) shouldBe null }
    }

    @Test
    fun `§17_5 moedas nao entram no sorteio - so ids do catalogo`() {
        val inv = Inventory(mapOf("coins" to 0))
        val rng = Random(1)
        val ids = catalog.map { it.id }
        (1..500).mapNotNull { DailyGift.draw(inv, catalog, rng) }.all { it.boosterId in ids } shouldBe true
    }

    @Test
    fun `§17_5 a quantidade e limitada pelo que falta para 10`() {
        // b3 tem 9: só cabe 1, mesmo com a faixa 2 a 7
        val only = listOf(catalog.first { it.id == "b3" })
        val inv = Inventory(mapOf("b3" to 9))
        val gifts = (0..300).mapNotNull { DailyGift.draw(inv, only, Random(it)) }
        gifts.isNotEmpty() shouldBe true
        gifts.all { it.quantity == 1 } shouldBe true
    }
}
