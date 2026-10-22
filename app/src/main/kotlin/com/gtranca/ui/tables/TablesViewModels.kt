package com.gtranca.ui.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.Difficulty
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.Coins
import com.gtranca.engine.Wallet
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.PlayerProfile
import com.gtranca.game.TableOffer
import com.gtranca.game.PersonaGenerator
import com.gtranca.game.Tables
import com.gtranca.game.WriteQueue
import com.gtranca.game.isAsciiDigits
import com.gtranca.game.parseTargetScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Problema na pontuação-alvo digitada. */
enum class TargetError {
    /** Não é um inteiro positivo (vazio, zero, sinal, letras…). */
    INVALID,

    /** Só dígitos, mas maior que o maior inteiro aceito. */
    TOO_LARGE,
}

/** Erro de um texto de pontuação: `null` se [parsed] é válido. */
internal fun targetErrorOf(text: String, parsed: Int?): TargetError? {
    if (parsed != null) return null
    val digits = text.trim()
    return if (digits.isAsciiDigits() && digits.trimStart('0').isNotEmpty() && digits.toIntOrNull() == null) {
        TargetError.TOO_LARGE
    } else {
        TargetError.INVALID
    }
}

/** E/S que falha não derruba a tela: vale como "nada". */
private suspend fun <T> safely(block: suspend () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

/** Lista de mesas abertas pelos jogadores virtuais (§14.4): sorteada a cada vez que a tela é aberta. */
class TablesViewModel(
    seed: Long = System.nanoTime(),
    private val settings: SettingsRepository? = null,
    wallet: WalletRepository? = null,
    private val writes: com.gtranca.game.WriteQueue? = null,
) : ViewModel() {
    /** §16 Wi-Fi do jogo: a mesma configuração do Perfil (uma só fonte, guardada). */
    val wifiOn: StateFlow<Boolean> = (settings?.settings?.map { it.wifiOn }?.catch { emit(false) } ?: kotlinx.coroutines.flow.flowOf(false))
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** §16 o botão de Wi-Fi grava a configuração do Perfil (ligar só depois da porta das permissões; a tela cuida disso). */
    fun setWifi(on: Boolean) {
        val repo = settings ?: return
        writes?.enqueue { repo.setWifiOn(on) } ?: viewModelScope.launch { repo.setWifiOn(on) }
    }

    private val _tables = MutableStateFlow(Tables.botTables(seed))
    val tables: StateFlow<List<TableOffer>> = _tables.asStateFlow()

    /** §14.6 saldo da carteira (as mesas acima dele ficam bloqueadas). */
    val balance: StateFlow<Int> = balanceFlow(wallet, viewModelScope)

    init {
        // O anfitrião nunca tem o primeiro nome do jogador (como os adversários, §14.1).
        settings?.let { repo ->
            viewModelScope.launch {
                val name = safely { PlayerProfile.decode(repo.settings.first().profileId).name } ?: return@launch
                if (name.isNotBlank()) _tables.value = Tables.botTables(seed, name)
            }
        }
    }

    fun table(id: Int): TableOffer? = _tables.value.firstOrNull { it.id == id }
}

/**
 * Mesa criada pelo jogador (§14.4, §14.5): os personagens são sorteados com a [seed] e mostrados antes de começar;
 * [reroll] sorteia outros, com a mesma configuração. O jogo que começa usa a semente da mesa mostrada.
 */
class CreatedTableViewModel(
    private val config: GameConfig,
    seed: Long = System.nanoTime(),
    settings: SettingsRepository? = null,
    wallet: WalletRepository? = null,
) : ViewModel() {
    /** §14.6 saldo da carteira. */
    val balance: StateFlow<Int> = balanceFlow(wallet, viewModelScope)

    private var playerName = ""
    private var seed = seed
    private val _table = MutableStateFlow(build())
    val table: StateFlow<TableOffer> = _table.asStateFlow()

    init {
        // Nenhum personagem leva o primeiro nome do jogador (§14.5).
        settings?.let { repo ->
            viewModelScope.launch {
                val name = safely { PlayerProfile.decode(repo.settings.first().profileId).name } ?: return@launch
                if (name.isNotBlank()) {
                    playerName = name
                    _table.value = build()
                }
            }
        }
    }

    private fun build() = TableOffer(
        id = Tables.CREATED_TABLE_ID,
        seed = seed,
        players = PersonaGenerator.opponents(seed, config.mode.seatCount - 1, config.difficulty, playerName),
        mode = config.mode,
        difficulty = config.difficulty,
        targetScore = config.targetScore,
        rules = config.rules,
        stake = config.stake,
    )

    /** Sorteia outros personagens para a mesma configuração. */
    fun reroll() {
        seed = Random.nextLong()
        _table.value = build()
    }
}

/** Regras escolhidas ao criar a mesa (§14.4). */
enum class RulesChoice { CLASSICA, PAULISTA, CUSTOM }

/** Estado de "Criar mesa" (§14, §14.4): modo, dificuldade (padrão médio), regras e pontuação-alvo (padrão 3000). */
data class CreateTableUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val rulesChoice: RulesChoice = RulesChoice.CLASSICA,
    /** Regras personalizadas do jogador (§14.4). */
    val customRules: RuleSet = Tables.defaultCustomRules("Minhas regras"),
    val targetText: String = RuleSet.DEFAULT.targetScore.toString(),
    /** Já leu o último modo e as regras personalizadas. */
    val loaded: Boolean = true,
    /** §14.6 aposta escolhida e saldo da carteira. */
    val stake: Int = Coins.DEFAULT_STAKE,
    val balance: Int = Coins.INITIAL_BALANCE,
    /** §16.2 tipo Multijogador: mesa sempre de 4 posições (o modo some da tela) e com [tableName]. */
    val multiplayer: Boolean = false,
    val tableName: String = "",
) {
    val targetScore: Int? get() = parseTargetScore(targetText)

    val targetError: TargetError? get() = targetErrorOf(targetText, targetScore)

    val rules: RuleSet
        get() = when (rulesChoice) {
            RulesChoice.CLASSICA -> RuleSet.CLASSICA
            RulesChoice.PAULISTA -> RuleSet.PAULISTA
            RulesChoice.CUSTOM -> customRules
        }

    /** §14.6 o saldo cobre a aposta. */
    val canAfford: Boolean get() = Coins.canJoin(balance, stake)

    val canCreate: Boolean get() = targetScore != null && loaded && canAfford

    fun toConfig(): GameConfig? = targetScore?.let { GameConfig(if (multiplayer) GameMode.DUPLAS else mode, difficulty, it, rules, stake) }

    companion object {
        const val MAX_TARGET: Int = Int.MAX_VALUE
    }
}

/** "Criar mesa": modo pré-selecionado com o último escolhido (§14), lembrado ao criar. */
class CreateTableViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
    wallet: WalletRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateTableUiState(loaded = settings == null))
    val uiState: StateFlow<CreateTableUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu no modo nesta tela: a preferência salva não o sobrescreve. */
    private var modeTouched = false

    init {
        wallet?.let { repo ->
            viewModelScope.launch {
                repo.wallet.catch { emit(Wallet()) }.collect { w -> _uiState.update { it.copy(balance = w.balance) } }
            }
        }
        settings?.let { repo ->
            viewModelScope.launch {
                val stored = repo.settings.catch { emit(Settings()) }.first()
                _uiState.update {
                    it.copy(
                        mode = if (modeTouched) it.mode else stored.lastMode,
                        customRules = stored.customRules ?: it.customRules,
                        loaded = true,
                    )
                }
            }
        }
    }

    /** Relê as regras personalizadas (voltando do editor) e, se [select], deixa "Personalizada" escolhida. */
    fun reloadCustomRules(select: Boolean) {
        val repo = settings ?: return
        viewModelScope.launch {
            val stored = safely { repo.settings.first() }?.customRules
            _uiState.update {
                it.copy(
                    customRules = stored ?: it.customRules,
                    rulesChoice = if (select) RulesChoice.CUSTOM else it.rulesChoice,
                )
            }
        }
    }

    fun onModeChange(mode: GameMode) {
        modeTouched = true
        _uiState.update { it.copy(mode = mode) }
    }

    fun onDifficultyChange(difficulty: Difficulty) = _uiState.update { it.copy(difficulty = difficulty) }

    /** §16.2 tipo da mesa: contra robôs ou multijogador. */
    fun onTypeChange(multiplayer: Boolean) = _uiState.update { it.copy(multiplayer = multiplayer) }

    /** §16.2 nome da mesa multijogador (até [TABLE_NAME_MAX] caracteres). */
    fun onTableNameChange(name: String) = _uiState.update { it.copy(tableName = name.take(TABLE_NAME_MAX)) }

    fun onRulesChange(choice: RulesChoice) = _uiState.update { it.copy(rulesChoice = choice) }

    fun onTargetChange(text: String) = _uiState.update { it.copy(targetText = text) }

    /** §14.6 aposta da mesa (uma das [Coins.STAKES]; outro valor é ignorado). */
    fun onStakeChange(stake: Int) = _uiState.update { if (stake in Coins.STAKES) it.copy(stake = stake) else it }

    /** "Criar mesa": devolve a configuração do jogo (e lembra o modo, §14), ou `null` se ainda não dá. */
    fun onCreate(): GameConfig? {
        val state = _uiState.value
        if (!state.canCreate) return null
        val config = state.toConfig() ?: return null
        // A mesa multijogador não muda o último modo escolhido contra robôs.
        if (state.multiplayer) return config
        settings?.let { repo ->
            if (writes != null) writes.enqueue { repo.setLastMode(config.mode) }
            else viewModelScope.launch { safely { repo.setLastMode(config.mode) } }
        }
        return config
    }
}

/** §16.2 tamanho máximo do nome da mesa multijogador (o anúncio corta o que não couber, AdvertCodec). */
const val TABLE_NAME_MAX: Int = 24

/** Estado do editor das regras personalizadas (§14.2, §14.4). */
data class RulesEditorUiState(
    val rules: RuleSet = Tables.defaultCustomRules("Minhas regras"),
    val cleanText: String = rules.cleanCanastaPoints.toString(),
    val dirtyText: String = rules.dirtyCanastaPoints.toString(),
    /** Penalidade do 3 preto na mão, digitada sem sinal (vale negativa, §12.2). */
    val blackThreeText: String = (-rules.handBlackThreePoints).toString(),
    val loaded: Boolean = true,
    val saving: Boolean = false,
    /** Gravou: a tela volta. */
    val saved: Boolean = false,
) {
    private fun points(text: String): Int? = text.trim().takeIf { it.isAsciiDigits() }?.toIntOrNull()

    val cleanError: Boolean get() = points(cleanText) == null
    /** §1 sem coringas não há canastra suja: o campo dela não conta. */
    val dirtyError: Boolean get() = rules.maxWildsPerMeld > 0 && points(dirtyText) == null
    val blackThreeError: Boolean get() = points(blackThreeText) == null

    /** As regras com os números digitados, ou `null` se algum é inválido (§14.2 faixas). */
    fun result(): RuleSet? {
        val clean = points(cleanText) ?: return null
        val black = points(blackThreeText) ?: return null
        // §1 sem coringas toda canastra é "a canastra": a suja e a exigência de limpa para bater não se aplicam.
        if (rules.maxWildsPerMeld == 0) {
            return rules.copy(
                cleanCanastaPoints = clean, dirtyCanastaPoints = clean, goOutRequiresCleanCanasta = false, handBlackThreePoints = -black,
            )
        }
        val dirty = points(dirtyText) ?: return null
        return rules.copy(cleanCanastaPoints = clean, dirtyCanastaPoints = dirty, handBlackThreePoints = -black)
    }

    val canSave: Boolean get() = loaded && !saving && result() != null
}

/** Editor das regras personalizadas do jogador (§14.4), aberto pelo Perfil ou por "Criar mesa". */
class RulesEditorViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
    private val defaultName: String = "Minhas regras",
) : ViewModel() {
    private val _uiState = MutableStateFlow(RulesEditorUiState(rules = Tables.defaultCustomRules(defaultName), loaded = settings == null))
    val uiState: StateFlow<RulesEditorUiState> = _uiState.asStateFlow()

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                val stored = repo.settings.catch { emit(Settings()) }.first().customRules
                _uiState.value = if (stored != null) fromRules(stored) else _uiState.value.copy(loaded = true)
            }
        }
    }

    private fun fromRules(rules: RuleSet) = RulesEditorUiState(
        rules = rules,
        cleanText = rules.cleanCanastaPoints.toString(),
        dirtyText = rules.dirtyCanastaPoints.toString(),
        blackThreeText = (-rules.handBlackThreePoints).toString(),
    )

    private fun edit(change: (RuleSet) -> RuleSet) = _uiState.update { it.copy(rules = change(it.rules)) }

    /** §14.3 parte dos valores de um perfil da casa (mantém a identidade das regras do jogador). */
    fun onStartFrom(house: RuleSet) {
        val current = _uiState.value.rules
        _uiState.value = fromRules(house.duplicate(current.id, current.name).copy(targetScore = current.targetScore))
    }

    fun onGroupRanks(value: GroupRanks) = edit { it.copy(groupRanks = value) }
    fun onMaxWilds(value: Int) = edit { it.copy(maxWildsPerMeld = value) }
    fun onMortoBecomesStock(value: Boolean) = edit { it.copy(mortoBecomesStock = value) }
    fun onGoOutRequiresClean(value: Boolean) = edit { it.copy(goOutRequiresCleanCanasta = value) }
    fun onCleanText(text: String) = _uiState.update { it.copy(cleanText = text) }
    fun onDirtyText(text: String) = _uiState.update { it.copy(dirtyText = text) }
    fun onBlackThreeText(text: String) = _uiState.update { it.copy(blackThreeText = text) }

    fun onSave() {
        val state = _uiState.value
        if (!state.canSave) return
        val rules = state.result() ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val repo = settings
            if (repo != null) {
                if (writes != null) writes.run { repo.setCustomRules(rules) } else safely { repo.setCustomRules(rules) }
            }
            _uiState.update { it.copy(rules = rules, saving = false, saved = true) }
        }
    }
}

/** §14.6 saldo da carteira como fluxo; sem carteira (testes), o saldo inicial. */
private fun balanceFlow(wallet: WalletRepository?, scope: kotlinx.coroutines.CoroutineScope): StateFlow<Int> {
    val state = MutableStateFlow(Coins.INITIAL_BALANCE)
    wallet?.let { repo ->
        scope.launch { repo.wallet.catch { emit(Wallet()) }.collect { state.value = it.balance } }
    }
    return state.asStateFlow()
}
