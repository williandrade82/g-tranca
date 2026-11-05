package com.gtranca.ui.online

import com.gtranca.data.Settings
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Shop
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §17.10/§18.5 Loja online (docs/telas.md 3.20a): mesmo painel/modal da Loja, carteira e inventário no servidor. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineShopViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val settings = FakeSettings(Settings(onlineServer = "servidor:8080"))
    private fun account() = OnlineAccount(FakeLobby(), settings) { "Ana" }

    private var keyCounter = 0

    private fun newVm(economy: FakeEconomy) = OnlineShopViewModel(
        account(), economy, BoosterCatalog.DEFAULT,
        newKey = { "k${++keyCounter}" },
    )

    @Test
    fun `lista as nove ofertas e o saldo da carteira online`() = runTest(dispatcher) {
        val vm = newVm(FakeEconomy(initialBalance = 100))
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineShopStatus.READY
        vm.uiState.value.shop.items.size shouldBe 9
        vm.uiState.value.shop.balance shouldBe 100
    }

    @Test
    fun `comprar debita o valor no servidor e fecha o modal`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        val vm = newVm(economy)
        advanceUntilIdle()
        val item = vm.uiState.value.shop.items[1] // b1 x5 por 30
        vm.onOffer(item)
        vm.onBuy()
        advanceUntilIdle()
        vm.uiState.value.shop.balance shouldBe 70
        vm.uiState.value.shop.selected shouldBe null
        vm.uiState.value.shop.bought shouldBe item
        economy.buyAttempts.size shouldBe 1
    }

    @Test
    fun `saldo insuficiente no servidor mostra o aviso e nao insiste sozinho`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        val vm = newVm(economy)
        advanceUntilIdle()
        val item = vm.uiState.value.shop.items.first { it.offer == Shop.OFFERS[0] } // 50 moedas
        vm.onOffer(item)
        // Outro aparelho gasta o saldo entre o modal abrir e o Comprar.
        economy.failNext = OnlineException(OnlineErrorKind.API, economyCode = com.gtranca.online.contracts.EconomyErrorCode.SALDO_INSUFICIENTE)
        vm.onBuy()
        advanceUntilIdle()
        vm.uiState.value.shop.notice shouldBe com.gtranca.ui.shop.ShopNotice.INSUFFICIENT
        economy.buyAttempts.size shouldBe 1
    }

    @Test
    fun `falha de rede fecha o modal e avisa na tela - repetir usa a mesma chave e cobra uma vez`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        val vm = newVm(economy)
        advanceUntilIdle()
        val item = vm.uiState.value.shop.items[2] // b1 x1 por 10
        vm.onOffer(item)
        economy.failNext = OnlineException(OnlineErrorKind.NETWORK)
        vm.onBuy()
        advanceUntilIdle()
        vm.uiState.value.shop.selected shouldBe null
        vm.uiState.value.problem shouldBe OnlineProblem.NETWORK
        // Enquanto a compra está pendente, nenhuma outra oferta abre.
        vm.onOffer(vm.uiState.value.shop.items[0])
        vm.uiState.value.shop.selected shouldBe null
        vm.retry()
        advanceUntilIdle()
        vm.uiState.value.shop.bought shouldBe item
        vm.uiState.value.shop.balance shouldBe 90
        economy.buyAttempts.size shouldBe 2
        economy.buyAttempts.toSet().size shouldBe 1 // a mesma (conta, chave) nas duas tentativas
    }

    @Test
    fun `resposta sem codigo reconhecivel mantem a chave - repetir nao cobra duas vezes`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        val vm = newVm(economy)
        advanceUntilIdle()
        val item = vm.uiState.value.shop.items[2] // b1 x1 por 10
        vm.onOffer(item)
        // Uma falha sem corpo reconhecível (ex.: resposta 200 que não deu para interpretar) não garante que o
        // servidor NÃO processou o pedido - a chave não pode ser esquecida aqui.
        economy.failNext = OnlineException(OnlineErrorKind.UNEXPECTED)
        vm.onBuy()
        advanceUntilIdle()
        vm.uiState.value.shop.selected shouldBe null
        vm.uiState.value.problem shouldBe OnlineProblem.UNEXPECTED
        vm.retry()
        advanceUntilIdle()
        vm.uiState.value.shop.bought shouldBe item
        vm.uiState.value.shop.balance shouldBe 90
        economy.buyAttempts.size shouldBe 2
        economy.buyAttempts.toSet().size shouldBe 1
    }

    @Test
    fun `toque duplo em Comprar envia um pedido so`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        val vm = newVm(economy)
        advanceUntilIdle()
        vm.onOffer(vm.uiState.value.shop.items[2])
        vm.onBuy()
        vm.onBuy()
        advanceUntilIdle()
        economy.buyAttempts.size shouldBe 1
        vm.uiState.value.shop.balance shouldBe 90
    }

    @Test
    fun `falha ao abrir a loja mostra erro e Tentar de novo recupera`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        economy.failNext = OnlineException(OnlineErrorKind.MAINTENANCE)
        val vm = newVm(economy)
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineShopStatus.ERROR
        vm.uiState.value.problem shouldBe OnlineProblem.MAINTENANCE
        vm.retry()
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineShopStatus.READY
        vm.uiState.value.shop.balance shouldBe 100
    }
}
