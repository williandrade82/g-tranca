package com.gtranca.ui.inventory

import com.gtranca.data.InventoryRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
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

/** §17.2 Inventário: marcar até 3 boosters, gravar na hora, avisar quando não cabe (docs/telas.md 3.18). */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeInventory(
        override val catalog: List<BoosterDef>,
        start: Inventory,
    ) : InventoryRepository {
        val stock = MutableStateFlow(start)
        val marked = MutableStateFlow(emptyList<String>())
        override val inventory: Flow<Inventory> = stock
        override val selection: Flow<List<String>> = marked
        override suspend fun setSelection(ids: List<String>) { marked.value = Loadout.normalize(ids, stock.value, catalog) }
        override suspend fun confirmLoadout(selection: List<String>) = Loadout.confirm(selection, stock.value, catalog)
        override suspend fun claimGift(today: Long, random: Random): Gift? = null
    }

    private val four = BoosterCatalog.DEFAULT + BoosterDef("b4", "Quarto", "x", 1, 5, 1, 1)

    @Test
    fun `§17_2 o armario lista o catalogo com a quantidade e a marcacao`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory(mapOf("b1" to 3, "b2" to 0, "b3" to 7)))
        val vm = InventoryViewModel(repo)
        vm.onToggle("b1")
        advanceUntilIdle()
        vm.state.value.items.map { Triple(it.def.id, it.quantity, it.selected) } shouldBe
            listOf(Triple("b1", 3, true), Triple("b2", 0, false), Triple("b3", 7, false))
    }

    @Test
    fun `§17_2 a quarta marcacao e recusada com aviso e desmarcar libera`() = runTest(dispatcher) {
        val repo = FakeInventory(four, Inventory.initial(four))
        val vm = InventoryViewModel(repo)
        listOf("b1", "b2", "b3").forEach { vm.onToggle(it) }
        advanceUntilIdle()
        vm.state.value.selectedCount shouldBe 3
        vm.onToggle("b4")
        advanceUntilIdle()
        repo.marked.value shouldBe listOf("b1", "b2", "b3")
        vm.state.value.notice shouldBe InventoryNotice.MAX_SELECTED
        vm.onToggle("b2")
        advanceUntilIdle()
        repo.marked.value shouldBe listOf("b1", "b3")
        vm.state.value.notice shouldBe null
    }

    @Test
    fun `§17_2 booster sem estoque nao marca e avisa que acabou`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory(mapOf("b1" to 0)))
        val vm = InventoryViewModel(repo)
        vm.onToggle("b1")
        advanceUntilIdle()
        repo.marked.value shouldBe emptyList()
        vm.state.value.notice shouldBe InventoryNotice.NO_STOCK
    }

    @Test
    fun `§17_2 moedas nao se marcam - fora do catalogo nada muda`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory.initial())
        val vm = InventoryViewModel(repo)
        vm.onToggle("coins")
        advanceUntilIdle()
        repo.marked.value shouldBe emptyList()
    }

    @Test
    fun `§17_2 tocar num item mostra a descricao dele na caixa de texto e marca o booster`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory.initial())
        val vm = InventoryViewModel(repo)
        vm.state.value.info shouldBe null
        vm.onItem("b2")
        advanceUntilIdle()
        vm.state.value.info shouldBe "b2"
        repo.marked.value shouldBe listOf("b2")
        // outro item troca a descrição
        vm.onItem("b3")
        advanceUntilIdle()
        vm.state.value.info shouldBe "b3"
    }

    @Test
    fun `§17_2 as moedas mostram a descricao mas nao se marcam nem avisam falta de estoque`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory.initial())
        val vm = InventoryViewModel(repo)
        vm.onItem("coins")
        advanceUntilIdle()
        vm.state.value.info shouldBe "coins"
        repo.marked.value shouldBe emptyList()
        vm.state.value.notice shouldBe null
    }

    @Test
    fun `§17_2 item sem estoque mostra a descricao e avisa que acabou`() = runTest(dispatcher) {
        val repo = FakeInventory(BoosterCatalog.DEFAULT, Inventory(mapOf("b1" to 0)))
        val vm = InventoryViewModel(repo)
        vm.onItem("b1")
        advanceUntilIdle()
        vm.state.value.info shouldBe "b1"
        vm.state.value.notice shouldBe InventoryNotice.NO_STOCK
    }
}
