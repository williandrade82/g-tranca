package com.gtranca.engine

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** §17.10 Loja: as nove ofertas e a regra da compra. */
class ShopTest {
    private val inventory = Inventory(mapOf("b1" to 5, "b2" to 5, "b3" to 5))

    @Test
    fun `§17_10 as nove ofertas - b1 b2 b3 em pacotes de 10 por 50, 5 por 30 e 1 por 10`() {
        Shop.OFFERS.map { Triple(it.boosterId, it.quantity, it.price) } shouldBe listOf(
            Triple("b1", 10, 50), Triple("b1", 5, 30), Triple("b1", 1, 10),
            Triple("b2", 10, 50), Triple("b2", 5, 30), Triple("b2", 1, 10),
            Triple("b3", 10, 50), Triple("b3", 5, 30), Triple("b3", 1, 10),
        )
    }

    @Test
    fun `§17_10 toda oferta e de um booster do catalogo`() {
        Shop.OFFERS.all { offer -> BoosterCatalog.DEFAULT.any { it.id == offer.boosterId } } shouldBe true
    }

    @Test
    fun `§17_10 comprar debita o valor e soma a quantidade ao booster certo`() {
        val bought = Shop.purchase(Wallet(100), inventory, ShopOffer("b2", 5, 30)).shouldNotBeNull()
        bought.wallet.balance shouldBe 70
        bought.inventory.quantity("b2") shouldBe 10
        bought.inventory.quantity("b1") shouldBe 5
        bought.inventory.quantity("b3") shouldBe 5
    }

    @Test
    fun `§17_10 a compra pode zerar o saldo`() {
        val bought = Shop.purchase(Wallet(50), inventory, ShopOffer("b1", 10, 50)).shouldNotBeNull()
        bought.wallet.balance shouldBe 0
        bought.inventory.quantity("b1") shouldBe 15
    }

    @Test
    fun `§17_10 saldo menor que o valor nao compra e nada muda`() {
        Shop.purchase(Wallet(49), inventory, ShopOffer("b1", 10, 50)).shouldBeNull()
        Shop.purchase(Wallet(0), inventory, ShopOffer("b3", 1, 10)).shouldBeNull()
    }

    @Test
    fun `§17_10 o saldo depois da compra e o saldo menos o valor`() {
        Shop.balanceAfter(120, ShopOffer("b1", 5, 30)) shouldBe 90
        Shop.balanceAfter(10, ShopOffer("b1", 10, 50)) shouldBe -40
    }

    @Test
    fun `§17_10 propriedade - o saldo nunca fica negativo e a compra so passa se o saldo cobre`() = runBlocking<Unit> {
        checkAll(Arb.int(0..300)) { balance ->
            Shop.OFFERS.forEach { offer ->
                val bought = Shop.purchase(Wallet(balance), inventory, offer)
                if (balance >= offer.price) {
                    bought.shouldNotBeNull().wallet.balance shouldBe balance - offer.price
                } else {
                    bought.shouldBeNull()
                }
            }
        }
    }
}
