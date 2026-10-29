package com.gtranca.ui.shop

import com.gtranca.data.ShopRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Shop
import com.gtranca.engine.ShopOffer
import com.gtranca.engine.Wallet
import com.gtranca.game.WriteQueue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §17.10 Loja: o modal de compra, o saldo que zera mas não negativa e a atualização na tela (docs/telas.md 3.20). */
@OptIn(ExperimentalCoroutinesApi::class)
class ShopViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val writeScope = CoroutineScope(dispatcher + SupervisorJob())

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private class FakeWallet(start: Int) : WalletRepository {
        val state = MutableStateFlow(Wallet(start))
        override val wallet: Flow<Wallet> = state
        override val lifetimeNet: Flow<Int> = emptyFlow()
        override suspend fun debit(stake: Int): Boolean = false
        override suspend fun claimDaily(today: Long): Boolean = false
    }

    private class FakeShop(private val wallet: FakeWallet) : ShopRepository {
        val bought = mutableListOf<ShopOffer>()
        var recovered = 0
        var failWith: Exception? = null
        override suspend fun purchase(offer: ShopOffer): Boolean {
            failWith?.let { throw it }
            val debited = wallet.state.value.debit(offer.price) ?: return false
            wallet.state.value = debited
            bought += offer
            return true
        }
        override suspend fun recoverPending() { recovered++ }
    }

    private fun TestScope.vm(balance: Int): Triple<ShopViewModel, FakeWallet, FakeShop> {
        val wallet = FakeWallet(balance)
        val shop = FakeShop(wallet)
        return Triple(ShopViewModel(wallet, shop, BoosterCatalog.DEFAULT, WriteQueue(writeScope)), wallet, shop)
    }

    @Test
    fun `§17_10 a loja lista as nove ofertas com o booster do catalogo`() = runTest(dispatcher) {
        val (vm, _, shop) = vm(100)
        advanceUntilIdle()
        vm.state.value.items.map { Triple(it.def.name, it.offer.quantity, it.offer.price) } shouldBe listOf(
            Triple("Green-LED", 10, 50), Triple("Green-LED", 5, 30), Triple("Green-LED", 1, 10),
            Triple("Yellow-LED", 10, 50), Triple("Yellow-LED", 5, 30), Triple("Yellow-LED", 1, 10),
            Triple("Reações", 10, 50), Triple("Reações", 5, 30), Triple("Reações", 1, 10),
        )
        vm.state.value.balance shouldBe 100
        // abrir a loja termina uma compra interrompida
        shop.recovered shouldBe 1
    }

    @Test
    fun `§17_10 tocar na oferta abre o modal com o saldo de agora e o de depois`() = runTest(dispatcher) {
        val (vm, _, _) = vm(100)
        advanceUntilIdle()
        val item = vm.state.value.items[1] // b1 x5 por 30
        vm.onOffer(item)
        advanceUntilIdle()
        vm.state.value.selected shouldBe item
        vm.state.value.canBuy shouldBe true
        item.balanceAfter(vm.state.value.balance!!) shouldBe 70
        vm.onCancel()
        advanceUntilIdle()
        vm.state.value.selected shouldBe null
    }

    @Test
    fun `§17_10 comprar atualiza o saldo na tela, fecha o modal e avisa`() = runTest(dispatcher) {
        val (vm, wallet, shop) = vm(100)
        advanceUntilIdle()
        val item = vm.state.value.items[1]
        vm.onOffer(item)
        vm.onBuy()
        advanceUntilIdle()
        wallet.state.value.balance shouldBe 70
        vm.state.value.balance shouldBe 70
        vm.state.value.selected shouldBe null
        vm.state.value.bought shouldBe item
        shop.bought shouldBe listOf(item.offer)
    }

    @Test
    fun `§17_10 a compra pode deixar o saldo zerado`() = runTest(dispatcher) {
        val (vm, wallet, shop) = vm(50)
        advanceUntilIdle()
        vm.onOffer(vm.state.value.items.first { it.offer == Shop.OFFERS[0] })
        advanceUntilIdle()
        vm.state.value.canBuy shouldBe true
        vm.onBuy()
        advanceUntilIdle()
        wallet.state.value.balance shouldBe 0
        shop.bought.size shouldBe 1
    }

    @Test
    fun `§17_10 saldo menor que o valor nao compra - o botao fica desativado e nada e debitado`() = runTest(dispatcher) {
        val (vm, wallet, shop) = vm(49)
        advanceUntilIdle()
        val item = vm.state.value.items[0] // 50 moedas
        vm.onOffer(item)
        advanceUntilIdle()
        vm.state.value.canBuy shouldBe false
        item.balanceAfter(vm.state.value.balance!!) shouldBe -1
        vm.onBuy()
        advanceUntilIdle()
        shop.bought shouldBe emptyList()
        wallet.state.value.balance shouldBe 49
        vm.state.value.selected shouldBe item
    }

    @Test
    fun `§17_10 toque duplo em Comprar compra uma vez so`() = runTest(dispatcher) {
        val (vm, wallet, shop) = vm(100)
        advanceUntilIdle()
        vm.onOffer(vm.state.value.items[2]) // b1 x1 por 10
        vm.onBuy()
        vm.onBuy()
        advanceUntilIdle()
        shop.bought.size shouldBe 1
        wallet.state.value.balance shouldBe 90
    }

    @Test
    fun `§17_10 falha ao gravar mantem o modal e mostra o aviso`() = runTest(dispatcher) {
        val (vm, _, shop) = vm(100)
        advanceUntilIdle()
        shop.failWith = java.io.IOException("disco")
        vm.onOffer(vm.state.value.items[0])
        vm.onBuy()
        advanceUntilIdle()
        vm.state.value.notice shouldBe ShopNotice.FAILED
        vm.state.value.buying shouldBe false
        vm.state.value.selected?.offer shouldBe Shop.OFFERS[0]
        vm.state.value.bought shouldBe null
    }
}
