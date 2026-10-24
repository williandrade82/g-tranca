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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
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
        override suspend fun setCreateTable(text: String) = state.update { it.copy(createTable = text) }
    }

    private class FakeWallet(balance: Int) : com.gtranca.data.WalletRepository {
        override val wallet: Flow<com.gtranca.engine.Wallet> = MutableStateFlow(com.gtranca.engine.Wallet(balance = balance))
        override val lifetimeNet: Flow<Int> = MutableStateFlow(0)
        override suspend fun debit(stake: Int): Boolean = true
        override suspend fun claimDaily(today: Long): Boolean = false
    }

    @Test
    fun `Criar mesa grava a configuracao ao criar e a recarrega ao reabrir`() = withMain {
        val settings = FakeSettings()
        val vm = CreateTableViewModel(settings)
        advanceUntilIdle()
        vm.onModeChange(GameMode.DUPLAS)
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.onRulesChange(RulesChoice.PAULISTA)
        vm.onStakeChange(250)
        vm.onTargetChange("2000")
        vm.onCreate().shouldNotBeNull()
        advanceUntilIdle()
        val again = CreateTableViewModel(settings)
        advanceUntilIdle()
        again.uiState.value.let {
            it.mode shouldBe GameMode.DUPLAS
            it.difficulty shouldBe Difficulty.DIFICIL
            it.rulesChoice shouldBe RulesChoice.PAULISTA
            it.stake shouldBe 250
            it.targetText shouldBe "2000"
            it.multiplayer shouldBe false
        }
    }

    @Test
    fun `Criar mesa ajusta ao carregar - aposta acima do saldo, Personalizada sem regras e texto ilegivel`() = withMain {
        val saved = listOf("1", "false", "", "DUPLAS", "facil", "CUSTOM", "500", "abc").joinToString("	")
        val vm = CreateTableViewModel(FakeSettings(Settings(createTable = saved)), wallet = FakeWallet(120))
        advanceUntilIdle()
        vm.uiState.value.let {
            it.stake shouldBe 100
            it.rulesChoice shouldBe RulesChoice.CLASSICA
            it.targetText shouldBe "3000"
            it.mode shouldBe GameMode.DUPLAS
        }
        val broken = CreateTableViewModel(FakeSettings(Settings(createTable = "lixo")))
        advanceUntilIdle()
        broken.uiState.value.rulesChoice shouldBe RulesChoice.CLASSICA
    }

    @Test
    fun `§16 o botao de Wi-Fi reflete e grava a configuracao do Perfil`() = withMain {
        val settings = FakeSettings(Settings(wifiOn = true))
        val vm = TablesViewModel(seed = 1, settings = settings)
        advanceUntilIdle()
        vm.wifiOn.value shouldBe true
        // Tocar no botão grava no Perfil.
        vm.setWifi(false)
        advanceUntilIdle()
        settings.state.value.wifiOn shouldBe false
        vm.wifiOn.value shouldBe false
        // Mudar no Perfil reflete no botão.
        settings.setWifiOn(true)
        advanceUntilIdle()
        vm.wifiOn.value shouldBe true
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
    fun `§16_2 criar mesa multijogador e sempre de 4 posicoes, com nome curto, sem mudar o ultimo modo`() = withMain {
        val settings = FakeSettings(Settings(lastMode = GameMode.INDIVIDUAL))
        val vm = CreateTableViewModel(settings)
        advanceUntilIdle()
        vm.onTypeChange(true)
        vm.onTableNameChange("Mesa da família no domingo à tarde com todos")
        vm.uiState.value.tableName.length shouldBe TABLE_NAME_MAX
        val config = vm.onCreate()!!
        config.mode shouldBe GameMode.DUPLAS
        advanceUntilIdle()
        settings.state.value.lastMode shouldBe GameMode.INDIVIDUAL
    }

    @Test
    fun `§14_4 mesas dos jogadores virtuais usam perfis da casa e metas usuais`() {
        val tables = Tables.botTables(seed = 42)
        tables.size shouldBe Tables.BOT_TABLE_COUNT
        tables.map { it.rules }.toSet() shouldBe RuleSet.HOUSE.toSet()
        tables.forEach { (it.targetScore in Tables.BOT_TARGETS) shouldBe true }
        tables.map { it.host.firstName }.distinct().size shouldBe tables.size
        Tables.botTables(seed = 42) shouldBe tables
        val first = tables.first()
        first.toConfig() shouldBe GameConfig(first.mode, first.difficulty, first.targetScore, first.rules, first.stake)
    }

    @Test
    fun `§14_4 cada mesa ja traz os personagens que vao jogar no nivel da sua dificuldade`() {
        for (seed in 1L..40L) {
            Tables.botTables(seed, avoidName = "Bia").forEach { table ->
                table.players.size shouldBe table.mode.seatCount - 1
                table.players.map { it.character!!.profile }.toSet().size shouldBe table.players.size
                table.players.forEach { it.character!!.level shouldBe com.gtranca.game.BotLevel.of(table.difficulty) }
                // os mesmos que o jogo sorteia com a semente da mesa
                table.players shouldBe com.gtranca.game.PersonaGenerator.opponents(table.seed, table.mode.seatCount - 1, table.difficulty, "Bia")
                table.host shouldBe table.players.first()
            }
        }
        // alguma mesa de duplas mostra os três
        (0L..40L).flatMap { Tables.botTables(it) }.any { it.mode == com.gtranca.engine.model.GameMode.DUPLAS && it.players.size == 3 } shouldBe true
    }

    @Test
    fun `§14_6 cada mesa traz uma das apostas e o sorteio cobre todas`() {
        val stakes = (1L..60L).flatMap { Tables.botTables(it) }.map { it.stake }
        stakes.all { it in Coins.STAKES } shouldBe true
        stakes.toSet() shouldBe Coins.STAKES.toSet()
    }

    @Test
    fun `§14_6 criar mesa - aposta padrao 50, escolhe entre as quatro e so cria com saldo`() {
        val vm = CreateTableViewModel()
        vm.uiState.value.stake shouldBe 50
        Coins.STAKES.forEach { vm.onStakeChange(it); vm.uiState.value.stake shouldBe it }
        vm.onStakeChange(77) // fora das opções: ignorada
        vm.uiState.value.stake shouldBe 500
        var state = vm.uiState.value
        state.canAfford shouldBe true // saldo inicial 1000
        state.toConfig()!!.stake shouldBe 500
        state = state.copy(balance = 499)
        state.canAfford shouldBe false
        state.canCreate shouldBe false
        state.copy(balance = 500).canCreate shouldBe true
    }

    @Test
    fun `§14 padroes de criar mesa - medio, Classica e alvo 3000`() {
        val state = CreateTableViewModel().uiState.value
        state.difficulty shouldBe Difficulty.MEDIO
        state.rulesChoice shouldBe RulesChoice.CLASSICA
        state.targetScore shouldBe 3000
        state.canCreate shouldBe true
        state.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000, RuleSet.CLASSICA, Coins.DEFAULT_STAKE)
    }

    @Test
    fun `§14 alvo deve ser inteiro positivo, e o texto nunca e truncado`() {
        val vm = CreateTableViewModel()
        vm.onTargetChange("0")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.uiState.value.canCreate shouldBe false
        vm.onCreate().shouldBeNull()
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
        vm.onCreate() shouldBe GameConfig(GameMode.DUPLAS, Difficulty.DIFICIL, 1000, RuleSet.PAULISTA, Coins.DEFAULT_STAKE)
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
    fun `§14 modo vem com o ultimo escolhido e e lembrado ao criar a mesa`() = withMain {
        val settings = FakeSettings(Settings(lastMode = GameMode.DUPLAS))
        val vm = CreateTableViewModel(settings)
        vm.uiState.value.canCreate shouldBe false
        advanceUntilIdle()
        vm.uiState.value.mode shouldBe GameMode.DUPLAS
        vm.onModeChange(GameMode.INDIVIDUAL)
        vm.onCreate()?.mode shouldBe GameMode.INDIVIDUAL
        advanceUntilIdle()
        settings.state.value.lastMode shouldBe GameMode.INDIVIDUAL
    }

    @Test
    fun `§14_4 Personalizada usa as regras do jogador e volta escolhida do editor`() = withMain {
        val mine = Tables.defaultCustomRules("Minhas regras").copy(handBlackThreePoints = -50)
        val settings = FakeSettings(Settings(customRules = mine))
        val vm = CreateTableViewModel(settings)
        advanceUntilIdle()
        vm.uiState.value.customRules shouldBe mine
        val edited = mine.copy(groupRanks = GroupRanks.NONE)
        settings.setCustomRules(edited)
        vm.reloadCustomRules(select = true)
        advanceUntilIdle()
        vm.uiState.value.rulesChoice shouldBe RulesChoice.CUSTOM
        vm.onCreate()?.rules shouldBe edited
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
        val create = CreateTableViewModel(broken)
        val editor = RulesEditorViewModel(broken)
        val tables = TablesViewModel(seed = 1, settings = broken)
        advanceUntilIdle()
        create.uiState.value.mode shouldBe GameMode.INDIVIDUAL
        create.onCreate()?.rules shouldBe RuleSet.CLASSICA
        editor.uiState.value.loaded shouldBe true
        tables.tables.value.map { it.rules }.toSet() shouldContainAll RuleSet.HOUSE
        advanceUntilIdle()
    }
}
