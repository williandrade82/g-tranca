package com.gtranca.ui.tables

import com.gtranca.ai.Difficulty
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.Coins
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Tables
import com.gtranca.game.parseTargetScore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import java.io.IOException

class TablesViewModelsTest {

    private class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<Settings> = state
        override suspend fun setLastMode(mode: GameMode) = state.update { it.copy(lastMode = mode) }
        override suspend fun setHandSort(id: String) = state.update { it.copy(handSortId = id) }
        override suspend fun setCustomRules(rules: RuleSet) = state.update { it.copy(customRules = rules) }
        override suspend fun setWifiOn(on: Boolean) = state.update { it.copy(wifiOn = on) }
        override suspend fun setTableSetup(setup: com.gtranca.data.TableSetup) = state.update { it.copy(tableSetup = setup) }
    }

    private class FakeWallet(balance: Int) : com.gtranca.data.WalletRepository {
        override val wallet: Flow<com.gtranca.engine.Wallet> = MutableStateFlow(com.gtranca.engine.Wallet(balance = balance))
        override val lifetimeNet: Flow<Int> = MutableStateFlow(0)
        override suspend fun debit(stake: Int): Boolean = true
        override suspend fun claimDaily(today: Long): Boolean = false
    }

    @Test
    fun `§14_4 a configuracao grava o modo, nivel, aposta, regras e alvo ao selecionar jogadores e a recarrega ao reabrir`() = withMain {
        val settings = FakeSettings()
        val vm = TablesViewModel(settings)
        advanceUntilIdle()
        vm.onModeChange(GameMode.DUPLAS)
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.onRulesChange(RulesChoice.PAULISTA)
        vm.onStakeChange(300)
        vm.onTargetChange("2000")
        vm.onSelectPlayers().shouldNotBeNull()
        advanceUntilIdle()
        val again = TablesViewModel(settings)
        advanceUntilIdle()
        again.uiState.value.let {
            it.mode shouldBe GameMode.DUPLAS
            it.difficulty shouldBe Difficulty.DIFICIL
            it.rulesChoice shouldBe RulesChoice.PAULISTA
            it.stake shouldBe 300
            it.targetText shouldBe "2000"
        }
    }

    @Test
    fun `§14_4 a montagem ajusta ao carregar - aposta acima do saldo, Personalizada sem regras e texto ilegivel`() = withMain {
        val saved = listOf("1", "false", "", "DUPLAS", "facil", "CUSTOM", "500", "abc").joinToString("	")
        val vm = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup.fromLegacy(saved))), wallet = FakeWallet(120))
        advanceUntilIdle()
        vm.uiState.value.let {
            // 500 não existe no nível Iniciante (50/80/100): vira 100; o saldo (120) cobre
            it.stake shouldBe 100
            it.rulesChoice shouldBe RulesChoice.CLASSICA
            it.targetText shouldBe "3000"
            it.mode shouldBe GameMode.DUPLAS
        }
        val broken = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup.decode("lixo"))))
        advanceUntilIdle()
        broken.uiState.value.rulesChoice shouldBe RulesChoice.CLASSICA
    }

    private fun <T> withMain(block: suspend kotlinx.coroutines.test.TestScope.() -> T) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `§14_6 montagem - aposta padrao e o 1o valor do nivel, troca de nivel mantem o indice e o saldo limita`() {
        val vm = TablesViewModel()
        vm.uiState.value.difficulty shouldBe Difficulty.MEDIO
        vm.uiState.value.stake shouldBe 100
        Coins.stakesFor(1).forEach { vm.onStakeChange(it); vm.uiState.value.stake shouldBe it }
        vm.onStakeChange(77) // fora das opções: ignorada
        vm.uiState.value.stake shouldBe 200
        // mesmo índice (o 3º) ao trocar de nível
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.uiState.value.stake shouldBe 500
        vm.onDifficultyChange(Difficulty.FACIL)
        vm.uiState.value.stake shouldBe 100
        vm.onStakeChange(80)
        vm.onDifficultyChange(Difficulty.MEDIO)
        vm.uiState.value.stake shouldBe 150
        // 500 do nível Sênior não é coberto por 400: vira o maior coberto do nível (300)
        vm.onStakeChange(200)
        var state = vm.uiState.value.copy(balance = 400)
        state.canAfford shouldBe true
        state = state.copy(difficulty = Difficulty.DIFICIL, stake = Coins.fitStake(2, 2, 400))
        state.stake shouldBe 300
        // sem saldo nem para o menor: bloqueada
        val broke = vm.uiState.value.copy(balance = 99)
        broke.canAfford shouldBe false
        broke.canSelect shouldBe false
        broke.copy(balance = 200).canSelect shouldBe true
    }

    @Test
    fun `§14 padroes da montagem - medio, Classica e alvo 3000`() {
        val state = TablesViewModel().uiState.value
        state.difficulty shouldBe Difficulty.MEDIO
        state.rulesChoice shouldBe RulesChoice.CLASSICA
        state.targetScore shouldBe 3000
        state.canSelect shouldBe true
        state.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000, RuleSet.CLASSICA, 100)
    }

    @Test
    fun `§14 alvo deve ser inteiro positivo, e o texto nunca e truncado`() {
        val vm = TablesViewModel()
        vm.onTargetChange("0")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.uiState.value.canSelect shouldBe false
        vm.onSelectPlayers().shouldBeNull()
        vm.onTargetChange("15a0")
        vm.uiState.value.targetText shouldBe "15a0"
        vm.onTargetChange("1234567890123")
        vm.uiState.value.targetError shouldBe TargetError.TOO_LARGE
        vm.onTargetChange("+3000")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.onTargetChange("1000")
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.onModeChange(GameMode.DUPLAS)
        vm.onRulesChange(RulesChoice.PAULISTA)
        vm.onSelectPlayers() shouldBe GameConfig(GameMode.DUPLAS, Difficulty.DIFICIL, 1000, RuleSet.PAULISTA, 200)
    }

    @Test
    fun `§14_4 configuracao antiga com robos gravados le sem eles e a aposta antiga vira a mais proxima do nivel`() = withMain {
        val old = com.gtranca.data.TableSetup.decode(
            """{"mode":"DUPLAS","difficultyId":"dificil","rules":"PAULISTA","stake":250,"target":"2000","robots":["zen","jovem"]}""",
        )
        val vm = TablesViewModel(FakeSettings(Settings(tableSetup = old)), wallet = FakeWallet(1_000))
        advanceUntilIdle()
        vm.uiState.value.let {
            it.mode shouldBe GameMode.DUPLAS
            it.difficulty shouldBe Difficulty.DIFICIL
            // 250 no Sênior (200/300/500): empata entre 200 e 300, vale o menor
            it.stake shouldBe 200
            it.targetText shouldBe "2000"
        }
    }

    @Test
    fun `§14 leitura da pontuacao alvo - so digitos ASCII`() {
        parseTargetScore(" 2500 ") shouldBe 2500
        parseTargetScore("-5").shouldBeNull()
        parseTargetScore("99999999999").shouldBeNull()
        parseTargetScore("1.5").shouldBeNull()
        parseTargetScore("+3000").shouldBeNull()
        parseTargetScore("٣٠٠٠").shouldBeNull()
        parseTargetScore("３０００").shouldBeNull()
    }

    @Test
    fun `§14 modo vem com o ultimo escolhido e e lembrado ao selecionar jogadores`() = withMain {
        val settings = FakeSettings(Settings(lastMode = GameMode.DUPLAS))
        val vm = TablesViewModel(settings)
        vm.uiState.value.canSelect shouldBe false
        advanceUntilIdle()
        vm.uiState.value.mode shouldBe GameMode.DUPLAS
        vm.onModeChange(GameMode.INDIVIDUAL)
        vm.onSelectPlayers()?.mode shouldBe GameMode.INDIVIDUAL
        advanceUntilIdle()
        settings.state.value.lastMode shouldBe GameMode.INDIVIDUAL
    }

    @Test
    fun `§14_4 Personalizada usa as regras do jogador e volta escolhida do editor`() = withMain {
        val mine = Tables.defaultCustomRules("Minhas regras").copy(handBlackThreePoints = -50)
        val settings = FakeSettings(Settings(customRules = mine))
        val vm = TablesViewModel(settings)
        advanceUntilIdle()
        vm.uiState.value.customRules shouldBe mine
        val edited = mine.copy(groupRanks = GroupRanks.NONE)
        settings.setCustomRules(edited)
        vm.reloadCustomRules(select = true)
        advanceUntilIdle()
        vm.uiState.value.rulesChoice shouldBe RulesChoice.CUSTOM
        vm.onSelectPlayers()?.rules shouldBe edited
    }

    @Test
    fun `§14_4 editor parte da Classica, copia um perfil da casa e salva`() = withMain {
        val settings = FakeSettings()
        val vm = RulesEditorViewModel(settings, defaultName = "Minhas regras")
        advanceUntilIdle()
        vm.uiState.value.rules.baseId shouldBe RuleSet.CLASSICA.id
        vm.uiState.value.rules.id shouldBe Tables.CUSTOM_RULES_ID
        vm.onStartFrom(RuleSet.PAULISTA)
        with(vm.uiState.value) {
            rules.id shouldBe Tables.CUSTOM_RULES_ID
            rules.baseId shouldBe RuleSet.PAULISTA.id
            rules.groupRanks shouldBe GroupRanks.FACE_AND_ACE
            blackThreeText shouldBe "100"
        }
        vm.onMaxWilds(0)
        vm.onBlackThreeText("abc")
        vm.uiState.value.canSave shouldBe false
        vm.onBlackThreeText("30")
        vm.onCleanText("250")
        vm.onSave()
        advanceUntilIdle()
        vm.uiState.value.saved shouldBe true
        val stored = settings.state.value.customRules!!
        stored.maxWildsPerMeld shouldBe 0
        stored.handBlackThreePoints shouldBe -30
        stored.cleanCanastaPoints shouldBe 250
        stored.mortoBecomesStock shouldBe false
        // Reabrindo, o editor mostra as regras salvas.
        val again = RulesEditorViewModel(settings)
        advanceUntilIdle()
        again.uiState.value.rules shouldBe stored
    }

    @Test
    fun `§1 sem coringas o editor salva um valor so de canastra`() {
        val vm = RulesEditorViewModel()
        vm.onGoOutRequiresClean(true)
        vm.onMaxWilds(0)
        vm.onDirtyText("x")
        vm.onCleanText("300")
        vm.uiState.value.canSave shouldBe true
        val rules = vm.uiState.value.result()!!
        rules.cleanCanastaPoints shouldBe 300
        rules.dirtyCanastaPoints shouldBe 300
        rules.goOutRequiresCleanCanasta shouldBe false
    }

    @Test
    fun `preferencias ilegiveis viram padroes, sem derrubar as telas`() = withMain {
        val broken = object : SettingsRepository {
            override val settings: Flow<Settings> = flow { throw IOException("arquivo corrompido") }
            override suspend fun setLastMode(mode: GameMode) = throw IOException("disco cheio")
            override suspend fun setHandSort(id: String) = throw IOException("disco cheio")
        }
        val create = TablesViewModel(broken)
        val editor = RulesEditorViewModel(broken)
        advanceUntilIdle()
        create.uiState.value.mode shouldBe GameMode.INDIVIDUAL
        create.onSelectPlayers()?.rules shouldBe RuleSet.CLASSICA
        editor.uiState.value.loaded shouldBe true
        advanceUntilIdle()
    }
}
