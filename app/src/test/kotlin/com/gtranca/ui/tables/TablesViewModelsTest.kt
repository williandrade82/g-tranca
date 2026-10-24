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
    fun `§14_4 a montagem grava a configuracao ao escolher posicoes e a recarrega ao reabrir`() = withMain {
        val settings = FakeSettings()
        val vm = TablesViewModel(settings)
        advanceUntilIdle()
        vm.onModeChange(GameMode.DUPLAS)
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.onRulesChange(RulesChoice.PAULISTA)
        vm.onStakeChange(250)
        vm.onTargetChange("2000")
        vm.onChooseSeats().shouldNotBeNull()
        advanceUntilIdle()
        val again = TablesViewModel(settings)
        advanceUntilIdle()
        again.uiState.value.let {
            it.mode shouldBe GameMode.DUPLAS
            it.difficulty shouldBe Difficulty.DIFICIL
            it.rulesChoice shouldBe RulesChoice.PAULISTA
            it.stake shouldBe 250
            it.targetText shouldBe "2000"
        }
    }

    @Test
    fun `§14_4 a montagem ajusta ao carregar - aposta acima do saldo, Personalizada sem regras e texto ilegivel`() = withMain {
        val saved = listOf("1", "false", "", "DUPLAS", "facil", "CUSTOM", "500", "abc").joinToString("	")
        val vm = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup.fromLegacy(saved))), wallet = FakeWallet(120))
        advanceUntilIdle()
        vm.uiState.value.let {
            it.stake shouldBe 100
            it.rulesChoice shouldBe RulesChoice.CLASSICA
            it.targetText shouldBe "3000"
            it.mode shouldBe GameMode.DUPLAS
        }
        val broken = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup.decode("lixo"))))
        advanceUntilIdle()
        broken.uiState.value.rulesChoice shouldBe RulesChoice.CLASSICA
    }

    @Test
    fun `§16 o botao de Wi-Fi reflete e grava a configuracao do Perfil`() = withMain {
        val settings = FakeSettings(Settings(wifiOn = true))
        val vm = TablesViewModel(settings = settings)
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
    fun `§14_6 montagem - aposta padrao 50, escolhe entre as que o saldo cobre e so segue com saldo`() {
        val vm = TablesViewModel()
        vm.uiState.value.stake shouldBe 50
        Coins.STAKES.forEach { vm.onStakeChange(it); vm.uiState.value.stake shouldBe it }
        vm.onStakeChange(77) // fora das opções: ignorada
        vm.uiState.value.stake shouldBe 500
        var state = vm.uiState.value
        state.canAfford shouldBe true // saldo inicial 1000
        state.toConfig()!!.stake shouldBe 500
        state = state.copy(balance = 499)
        state.canAfford shouldBe false
        state.canChoose shouldBe false
        state.copy(balance = 500).canChoose shouldBe true
    }

    @Test
    fun `§14 padroes da montagem - medio, Classica e alvo 3000`() {
        val state = TablesViewModel().uiState.value
        state.difficulty shouldBe Difficulty.MEDIO
        state.rulesChoice shouldBe RulesChoice.CLASSICA
        state.targetScore shouldBe 3000
        state.canChoose shouldBe true
        state.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000, RuleSet.CLASSICA, Coins.DEFAULT_STAKE)
    }

    @Test
    fun `§14 alvo deve ser inteiro positivo, e o texto nunca e truncado`() {
        val vm = TablesViewModel()
        vm.onTargetChange("0")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.uiState.value.canChoose shouldBe false
        vm.onChooseSeats().shouldBeNull()
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
        vm.onChooseSeats()?.config shouldBe GameConfig(GameMode.DUPLAS, Difficulty.DIFICIL, 1000, RuleSet.PAULISTA, Coins.DEFAULT_STAKE)
    }

    @Test
    fun `§14_4 participantes - ate 3 marcados alem do jogador, na ordem da escolha`() {
        val vm = TablesViewModel()
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.ATLETA))
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.JOVEM))
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.SOBRIO))
        // o 4º toque não marca
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.ALTERNATIVO))
        vm.uiState.value.picks shouldBe listOf(
            Pick.Bot(com.gtranca.game.BotProfile.ATLETA),
            Pick.Bot(com.gtranca.game.BotProfile.JOVEM),
            Pick.Bot(com.gtranca.game.BotProfile.SOBRIO),
        )
        vm.uiState.value.participants shouldBe 4
        // desmarcar tira da lista
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.JOVEM))
        vm.uiState.value.picks.size shouldBe 2
    }

    @Test
    fun `§14_4 com 3 ou 4 participantes o modo e duplas, com 1 ou 2 o jogador escolhe`() {
        val vm = TablesViewModel()
        vm.onModeChange(GameMode.INDIVIDUAL)
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.ATLETA))
        vm.uiState.value.effectiveMode shouldBe GameMode.INDIVIDUAL
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.JOVEM))
        vm.uiState.value.modeLocked shouldBe true
        vm.uiState.value.effectiveMode shouldBe GameMode.DUPLAS
        // Individual desabilitado: o toque não muda nada
        vm.onModeChange(GameMode.INDIVIDUAL)
        vm.uiState.value.effectiveMode shouldBe GameMode.DUPLAS
        vm.onChooseSeats()!!.config.mode shouldBe GameMode.DUPLAS
        // de volta a 2 participantes, vale a escolha anterior
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.JOVEM))
        vm.uiState.value.effectiveMode shouldBe GameMode.INDIVIDUAL
    }

    @Test
    fun `§16_2 jogadores proximos - so marca quem esta por perto e quem some deixa de estar marcado`() {
        val vm = TablesViewModel()
        vm.onToggle(Pick.Near("abc234")) // ainda não achado
        vm.uiState.value.picks shouldBe emptyList()
        vm.onNearby(listOf(NearbyPlayer("abc234", "Bia")))
        vm.onToggle(Pick.Near("abc234"))
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.ATLETA))
        val chosen = vm.onChooseSeats()!!
        chosen.picks shouldBe listOf(Pick.Near("abc234"), Pick.Bot(com.gtranca.game.BotProfile.ATLETA))
        chosen.nearby shouldBe listOf(NearbyPlayer("abc234", "Bia"))
        vm.onNearby(emptyList())
        vm.uiState.value.picks shouldBe listOf(Pick.Bot(com.gtranca.game.BotProfile.ATLETA))
    }

    @Test
    fun `§14_4 grava os robos marcados e nao os jogadores proximos, e Individual com mais de 2 vira Duplas ao carregar`() = withMain {
        val settings = FakeSettings()
        val vm = TablesViewModel(settings)
        advanceUntilIdle()
        vm.onNearby(listOf(NearbyPlayer("abc234", "Bia")))
        vm.onToggle(Pick.Near("abc234"))
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.ALTERNATIVO))
        vm.onToggle(Pick.Bot(com.gtranca.game.BotProfile.SOBRIO))
        vm.onChooseSeats().shouldNotBeNull()
        advanceUntilIdle()
        settings.state.value.tableSetup!!.robots shouldBe listOf("alternativo", "sobrio")
        val again = TablesViewModel(settings)
        advanceUntilIdle()
        again.uiState.value.picks shouldBe listOf(Pick.Bot(com.gtranca.game.BotProfile.ALTERNATIVO), Pick.Bot(com.gtranca.game.BotProfile.SOBRIO))
        // Individual gravado com 2 robôs (3 participantes): carrega como Duplas.
        val odd = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup(GameMode.INDIVIDUAL, robots = listOf("jovem", "zen-desconhecido", "atleta")))))
        advanceUntilIdle()
        odd.uiState.value.mode shouldBe GameMode.DUPLAS
        odd.uiState.value.picks.size shouldBe 2
        // Individual com 1 robô continua Individual.
        val one = TablesViewModel(FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup(GameMode.INDIVIDUAL, robots = listOf("jovem")))))
        advanceUntilIdle()
        one.uiState.value.mode shouldBe GameMode.INDIVIDUAL
    }

    @Test
    fun `§16_2 recrutamento - marcar convida na hora, recusado nao marca, Recrutado por outro nao seleciona e quem sai deixa de estar marcado`() = withMain {
        val vm = TablesViewModel()
        val marked = mutableListOf<String>()
        val unmarked = mutableListOf<String>()
        var accept = true
        vm.recruiter = object : Recruiter {
            override suspend fun mark(player: NearbyPlayer): Boolean { marked += player.key; return accept }
            override suspend fun unmark(key: String) { unmarked += key }
        }
        val notices = mutableListOf<TablesNotice>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        vm.onNearby(listOf(NearbyPlayer("aaa222", "Bia"), NearbyPlayer("bbb333", "Caio"), NearbyPlayer("ccc444", "Duda", recruitedElsewhere = true)))
        vm.onToggle(Pick.Near("aaa222"))
        advanceUntilIdle()
        marked shouldBe listOf("aaa222")
        vm.uiState.value.picks shouldBe listOf(Pick.Near("aaa222"))
        // Convite não aceito pelo recrutamento: não marca, com aviso.
        accept = false
        vm.onToggle(Pick.Near("bbb333"))
        advanceUntilIdle()
        vm.uiState.value.picks shouldBe listOf(Pick.Near("aaa222"))
        notices shouldBe listOf(TablesNotice.MARK_FAILED)
        // Recrutado por outro anfitrião: nem tenta.
        vm.onToggle(Pick.Near("ccc444"))
        advanceUntilIdle()
        marked shouldBe listOf("aaa222", "bbb333")
        notices.last() shouldBe TablesNotice.RECRUITED_ELSEWHERE
        // Aceitou, e depois saiu (sumiu dos marcados do recrutamento): deixa de estar marcado.
        vm.onRecruits(listOf(com.gtranca.net.Recruit("aaa222", "Bia", accepted = true)))
        vm.uiState.value.accepted shouldBe setOf("aaa222")
        vm.onRecruits(emptyList())
        vm.uiState.value.picks shouldBe emptyList()
        // Desmarcar cancela o convite.
        accept = true
        vm.onToggle(Pick.Near("bbb333"))
        advanceUntilIdle()
        vm.onToggle(Pick.Near("bbb333"))
        advanceUntilIdle()
        unmarked shouldBe listOf("bbb333")
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
    fun `§14 modo vem com o ultimo escolhido e e lembrado ao escolher posicoes`() = withMain {
        val settings = FakeSettings(Settings(lastMode = GameMode.DUPLAS))
        val vm = TablesViewModel(settings)
        vm.uiState.value.canChoose shouldBe false
        advanceUntilIdle()
        vm.uiState.value.mode shouldBe GameMode.DUPLAS
        vm.onModeChange(GameMode.INDIVIDUAL)
        vm.onChooseSeats()?.config?.mode shouldBe GameMode.INDIVIDUAL
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
        vm.onChooseSeats()?.config?.rules shouldBe edited
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
        create.onChooseSeats()?.config?.rules shouldBe RuleSet.CLASSICA
        editor.uiState.value.loaded shouldBe true
        advanceUntilIdle()
    }
}
