package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gtranca.engine.ShopOffer
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** §17.10 Loja: compra de boosters com moedas, atômica entre a carteira e o inventário. */
class ShopRepositoryTest {

    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private val wallet = DataStoreStatsRepository(MemoryDataStore())
    private val inventory = DataStoreInventoryRepository(MemoryDataStore())
    private val shop = DefaultShopRepository(wallet, inventory)
    private val offer = ShopOffer("b1", 5, 30)

    private suspend fun balance() = wallet.wallet.first().balance
    private suspend fun quantity(id: String) = inventory.inventory.first().quantity(id)

    @Test
    fun `§17_10 comprar debita as moedas e soma ao inventario`() = runBlocking<Unit> {
        val before = balance()
        val owned = quantity("b1")
        shop.purchase(offer) shouldBe true
        balance() shouldBe before - 30
        quantity("b1") shouldBe owned + 5
        quantity("b2") shouldBe inventory.catalog.first { it.id == "b2" }.initialQuantity
    }

    @Test
    fun `§17_10 a compra pode zerar o saldo e a seguinte e recusada`() = runBlocking<Unit> {
        val owned = quantity("b2")
        shop.purchase(ShopOffer("b2", 10, balance())) shouldBe true
        balance() shouldBe 0
        quantity("b2") shouldBe owned + 10

        shop.purchase(ShopOffer("b3", 1, 10)) shouldBe false
        balance() shouldBe 0
    }

    @Test
    fun `§17_10 saldo menor que o valor nao compra e nada muda`() = runBlocking<Unit> {
        val before = balance()
        val owned = quantity("b2")
        shop.purchase(ShopOffer("b2", 10, before + 1)) shouldBe false
        balance() shouldBe before
        quantity("b2") shouldBe owned
        wallet.pendingPurchase() shouldBe null
    }

    @Test
    fun `§17_10 queda depois do debito - a compra pendente termina sem perder moedas nem somar duas vezes`() = runBlocking<Unit> {
        val before = balance()
        val owned = quantity("b1")
        // o app cai entre o debito (carteira) e a soma (inventario)
        wallet.debitPurchase("p1", offer) shouldBe true
        balance() shouldBe before - 30
        quantity("b1") shouldBe owned

        shop.recoverPending()
        quantity("b1") shouldBe owned + 5
        balance() shouldBe before - 30
        wallet.pendingPurchase() shouldBe null

        // repetir a recuperacao nao soma de novo
        shop.recoverPending()
        quantity("b1") shouldBe owned + 5
    }

    @Test
    fun `§17_10 queda depois da soma - a mesma compra nao soma duas vezes`() = runBlocking<Unit> {
        val before = balance()
        val owned = quantity("b1")
        // o app cai entre a soma e o fim (a compra continua pendente)
        wallet.debitPurchase("p1", offer) shouldBe true
        inventory.applyPurchase("p1", "b1", 5)

        shop.recoverPending()
        quantity("b1") shouldBe owned + 5
        balance() shouldBe before - 30
        wallet.pendingPurchase() shouldBe null
    }

    @Test
    fun `§17_10 falha ao somar no inventario deixa a compra pendente e a proxima abertura termina`() = runBlocking<Unit> {
        val failing = object : ShopStock {
            var fail = true
            override suspend fun applyPurchase(purchaseId: String, boosterId: String, quantity: Int) {
                if (fail) throw java.io.IOException("disco")
                inventory.applyPurchase(purchaseId, boosterId, quantity)
            }
        }
        val flaky = DefaultShopRepository(wallet, failing)
        val before = balance()
        val owned = quantity("b1")
        assertThrows<java.io.IOException> { flaky.purchase(offer) }
        balance() shouldBe before - 30
        quantity("b1") shouldBe owned

        failing.fail = false
        flaky.recoverPending()
        quantity("b1") shouldBe owned + 5
        balance() shouldBe before - 30
    }
}
