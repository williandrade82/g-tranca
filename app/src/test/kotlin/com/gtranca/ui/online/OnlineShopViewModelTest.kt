package com.gtranca.ui.online

import com.gtranca.data.Settings
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Shop
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.ShopConfigDto
import com.gtranca.online.contracts.ShopOfferDto
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
    fun `lista as doze ofertas e o saldo da carteira online`() = runTest(dispatcher) {
        val vm = newVm(FakeEconomy(initialBalance = 100))
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineShopStatus.READY
        vm.uiState.value.shop.items.size shouldBe 12
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

    // §17.10/§18.7: fecha o descompasso de docs/seguranca-pendencias.md (2026-11-08) — o app mostra o MESMO
    // preço/quantidade que o administrador configurou pelo console, não a constante fixa do :core-engine.
    @Test
    fun `mostra o preco e a quantidade configurados pelo administrador, nao o padrao fixo`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        economy.shopConfigValue = ShopConfigDto(
            Shop.OFFERS.mapIndexed { i, o -> if (i == 0) ShopOfferDto(i, o.boosterId, price = 1, quantity = 7) else ShopOfferDto(i, o.boosterId, o.price.toLong(), o.quantity.toLong()) },
            dailyGain = 999,
        )
        val vm = newVm(economy)
        advanceUntilIdle()
        val item = vm.uiState.value.shop.items[0]
        item.offer.price shouldBe 1
        item.offer.quantity shouldBe 7
        vm.uiState.value.staleOffers shouldBe false
    }

    // §18.7: a compra usa o ÍNDICE do servidor (ShopOfferDto.index), nunca offers.indexOf(item.offer) — um override
    // pode deixar duas ofertas iguais em (boosterId, quantity, price) e indexOf acharia a primeira (errada).
    @Test
    fun `compra usa o indice do servidor mesmo quando duas ofertas ficam iguais apos um override`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        // Oferta 0 (b1 x10 por 50) passa a valer o mesmo que a oferta 1 (b1 x5 por 30) virou: ambas b1 x5 por 30.
        economy.shopConfigValue = ShopConfigDto(
            Shop.OFFERS.mapIndexed { i, o ->
                when (i) {
                    0 -> ShopOfferDto(0, o.boosterId, price = 30, quantity = 5)
                    else -> ShopOfferDto(i, o.boosterId, o.price.toLong(), o.quantity.toLong())
                }
            },
            dailyGain = com.gtranca.engine.Coins.DAILY_GAIN.toLong(),
        )
        val vm = newVm(economy)
        advanceUntilIdle()
        val first = vm.uiState.value.shop.items[0] // índice 0, agora igual ao 1 em (boosterId, quantity, price)
        val second = vm.uiState.value.shop.items[1]
        second.offer shouldBe first.offer
        vm.onOffer(second)
        vm.onBuy()
        advanceUntilIdle()
        // Mesmo com os dois exibindo o mesmo preço/quantidade, o pedido leva o índice de QUEM foi tocado (1), nunca
        // o primeiro que bateria por igualdade de valor (0) — a gravação seria creditada à oferta errada no livro-razão.
        economy.buyOfferIndexAttempts shouldBe listOf(1)
        vm.uiState.value.shop.balance shouldBe 70

        vm.onOffer(first)
        vm.onBuy()
        advanceUntilIdle()
        economy.buyOfferIndexAttempts shouldBe listOf(1, 0)
        vm.uiState.value.shop.balance shouldBe 40
    }

    // §17.10: falha ao buscar a configuração não derruba a tela — mostra o padrão fixo com o aviso, a carteira
    // continua funcionando normalmente.
    @Test
    fun `falha ao buscar a configuracao mantem a loja aberta com o padrao e avisa que pode estar desatualizado`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        economy.failShopConfig = OnlineException(OnlineErrorKind.NETWORK)
        val vm = newVm(economy)
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineShopStatus.READY
        vm.uiState.value.shop.items.size shouldBe 12
        vm.uiState.value.shop.balance shouldBe 100
        vm.uiState.value.staleOffers shouldBe true
    }

    // *Tentar de novo* sem compra pendente relê a carteira e a configuração.
    @Test
    fun `tentar de novo sem compra pendente rele a configuracao tambem`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 100)
        economy.failShopConfig = OnlineException(OnlineErrorKind.NETWORK)
        val vm = newVm(economy)
        advanceUntilIdle()
        vm.uiState.value.staleOffers shouldBe true
        vm.retry()
        advanceUntilIdle()
        vm.uiState.value.staleOffers shouldBe false
    }
}
