package com.gtranca.ui.home

import com.gtranca.data.InventoryRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.DailyGift
import com.gtranca.engine.Gift
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §17.5 presente do dia: na primeira abertura do dia, só no Início, com o modal do booster sorteado. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeGiftTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Inventário que aplica de verdade o dia e o sorteio do motor (sem gravar em disco). */
    private class FakeInventory : InventoryRepository {
        override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT
        val stock = MutableStateFlow(Inventory.initial())
        var lastDay: Long? = null
        override val inventory: Flow<Inventory> = stock
        override val selection: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun setSelection(ids: List<String>) = Unit
        override suspend fun confirmLoadout(selection: List<String>) = Loadout.confirm(selection, stock.value, catalog)
        override suspend fun claimGift(today: Long, random: Random): Gift? {
            if (!DailyGift.isDue(today, lastDay)) return null
            lastDay = today
            val gift = DailyGift.draw(stock.value, catalog, random) ?: return null
            stock.value = stock.value.add(gift.boosterId, gift.quantity)
            return gift
        }
    }

    private fun seed(wins: Boolean): Int =
        (0..1_000).first { (DailyGift.draw(Inventory.initial(), BoosterCatalog.DEFAULT, Random(it)) != null) == wins }

    @Test
    fun `§17_5 ganhando o sorteio o inicio mostra o presente com nome e quantidade, e o prêmio ja esta no inventario`() = runTest(dispatcher) {
        val repo = FakeInventory()
        val vm = HomeViewModel(boosters = repo, today = { 20_000 }, giftRandom = { Random(seed(true)) })
        advanceUntilIdle()
        val gift = vm.uiState.value.gift!!
        gift.name shouldBe BoosterCatalog.DEFAULT.first { it.id == gift.boosterId }.name
        (gift.quantity >= 1) shouldBe true
        repo.stock.value.quantity(gift.boosterId) shouldBe 5 + gift.quantity
    }

    @Test
    fun `§17_5 fechar o modal tira o presente da tela`() = runTest(dispatcher) {
        val vm = HomeViewModel(boosters = FakeInventory(), today = { 20_000 }, giftRandom = { Random(seed(true)) })
        advanceUntilIdle()
        vm.onGiftDismissed()
        vm.uiState.value.gift shouldBe null
    }

    @Test
    fun `§17_5 perdendo o sorteio nao ha modal`() = runTest(dispatcher) {
        val vm = HomeViewModel(boosters = FakeInventory(), today = { 20_000 }, giftRandom = { Random(seed(false)) })
        advanceUntilIdle()
        vm.uiState.value.gift shouldBe null
    }

    @Test
    fun `§17_5 abrir o inicio de novo no mesmo dia nao sorteia outra vez`() = runTest(dispatcher) {
        val repo = FakeInventory()
        val first = HomeViewModel(boosters = repo, today = { 20_000 }, giftRandom = { Random(seed(true)) })
        advanceUntilIdle()
        (first.uiState.value.gift != null) shouldBe true
        val second = HomeViewModel(boosters = repo, today = { 20_000 }, giftRandom = { Random(seed(true)) })
        advanceUntilIdle()
        second.uiState.value.gift shouldBe null
        // no dia seguinte sorteia de novo
        val third = HomeViewModel(boosters = repo, today = { 20_001 }, giftRandom = { Random(seed(true)) })
        advanceUntilIdle()
        (third.uiState.value.gift != null) shouldBe true
    }

    @Test
    fun `§17_5 sem inventario (testes) nunca ha presente`() = runTest(dispatcher) {
        val vm = HomeViewModel()
        advanceUntilIdle()
        vm.uiState.value.gift shouldBe null
    }
}
