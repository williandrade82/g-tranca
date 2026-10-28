package com.gtranca.ui.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.Difficulty
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.data.TableSetup
import com.gtranca.data.WalletRepository
import com.gtranca.engine.Coins
import com.gtranca.engine.Wallet
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Tables
import com.gtranca.game.WriteQueue
import com.gtranca.game.isAsciiDigits
import com.gtranca.game.parseTargetScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Regras escolhidas na montagem da mesa (§14.4). */
enum class RulesChoice { CLASSICA, PAULISTA, CUSTOM }

/**
 * Estado da tela *Iniciar novo jogo* (§14.4, docs/telas.md 3.2): modo, dificuldade dos robôs (padrão médio), aposta (um
 * dos três valores do nível, §14.6), regras e pontuação-alvo (padrão 3000). Os lugares ficam na *Seleção de jogadores*.
 */
data class TableSetupUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val rulesChoice: RulesChoice = RulesChoice.CLASSICA,
    /** Regras personalizadas do jogador (§14.4). */
    val customRules: RuleSet = Tables.defaultCustomRules("Minhas regras"),
    val targetText: String = RuleSet.DEFAULT.targetScore.toString(),
    /** Já leu a última configuração e as regras personalizadas. */
    val loaded: Boolean = true,
    /** §14.6 aposta escolhida e saldo da carteira. */
    val stake: Int = Coins.stakesFor(1).first(),
    val balance: Int = Coins.INITIAL_BALANCE,
    /** §17.9 a mesa permite os boosters de skill. */
    val skillBoosters: Boolean = true,
) {
    val targetScore: Int? get() = parseTargetScore(targetText)

    val targetError: TargetError? get() = targetErrorOf(targetText, targetScore)

    val rules: RuleSet
        get() = when (rulesChoice) {
            RulesChoice.CLASSICA -> RuleSet.CLASSICA
            RulesChoice.PAULISTA -> RuleSet.PAULISTA
            RulesChoice.CUSTOM -> customRules
        }

    /** §14.6 os três valores de aposta do nível atual. */
    val stakes: List<Int> get() = Coins.stakesFor(difficulty.level)

    /** §14.6 o saldo cobre a aposta. */
    val canAfford: Boolean get() = Coins.canJoin(balance, stake)

    val canSelect: Boolean get() = targetScore != null && loaded && canAfford

    fun toConfig(): GameConfig? = targetScore?.let { GameConfig(mode, difficulty, it, rules, stake, skillBoosters) }

    companion object {
        const val MAX_TARGET: Int = Int.MAX_VALUE
    }
}

/**
 * *Iniciar novo jogo* (§14.4, docs/telas.md 3.2): as opções da mesa, sempre preenchidas com a última configuração gravada
 * (ajustada ao que vale agora); "Selecionar jogadores" grava a configuração e a devolve. Os lugares não são gravados.
 */
class TablesViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
    wallet: WalletRepository? = null,
) : ViewModel() {
    /** §16 Wi-Fi do jogo: a mesma configuração do Perfil (uma só fonte, guardada). */
    val wifiOn: StateFlow<Boolean> = (settings?.settings?.map { it.wifiOn }?.catch { emit(false) } ?: kotlinx.coroutines.flow.flowOf(false))
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** §16 o botão de Wi-Fi grava a configuração do Perfil (ligar só depois da porta das permissões; a tela cuida disso). */
    fun setWifi(on: Boolean) {
        val repo = settings ?: return
        writes?.enqueue { repo.setWifiOn(on) } ?: viewModelScope.launch { repo.setWifiOn(on) }
    }

    private val _uiState = MutableStateFlow(TableSetupUiState(loaded = settings == null))
    val uiState: StateFlow<TableSetupUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu nas opções nesta tela: a gravada não as sobrescreve. */
    private var touched = false

    init {
        wallet?.let { repo ->
            viewModelScope.launch {
                repo.wallet.catch { emit(Wallet()) }.collect { w -> _uiState.update { it.copy(balance = w.balance) } }
            }
        }
        settings?.let { repo ->
            viewModelScope.launch {
                val stored = repo.settings.catch { emit(Settings()) }.first()
                val balance = wallet?.let { w -> safely { w.wallet.first().balance } }
                _uiState.update {
                    val base = it.copy(
                        mode = if (touched) it.mode else stored.lastMode,
                        customRules = stored.customRules ?: it.customRules,
                        loaded = true,
                    )
                    if (touched) base else TableSetupPrefs.apply(base, stored.tableSetup, stored.customRules != null, balance)
                }
            }
        }
    }

    private fun edit(change: (TableSetupUiState) -> TableSetupUiState) {
        touched = true
        _uiState.update(change)
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

    fun onModeChange(mode: GameMode) = edit { it.copy(mode = mode) }

    /** §14.6 trocar o nível mantém o **mesmo índice** de aposta (limitado ao que o saldo cobre). */
    fun onDifficultyChange(difficulty: Difficulty) = edit {
        val index = Coins.stakeIndexOf(it.difficulty.level, it.stake)
        it.copy(difficulty = difficulty, stake = Coins.fitStake(difficulty.level, index, it.balance))
    }

    fun onRulesChange(choice: RulesChoice) = edit { it.copy(rulesChoice = choice) }

    fun onTargetChange(text: String) = edit { it.copy(targetText = text) }

    /** §17.9 permitir ou não os boosters de skill na mesa. */
    fun onSkillBoostersChange(allowed: Boolean) = edit { it.copy(skillBoosters = allowed) }

    /** §14.6 aposta da mesa (um dos três valores do nível que o saldo cobre; outro valor é ignorado). */
    fun onStakeChange(stake: Int) = edit {
        if (stake in it.stakes && Coins.canJoin(it.balance, stake)) it.copy(stake = stake) else it
    }

    /** "Selecionar jogadores": grava a configuração (sem os lugares) e a devolve, ou `null` se ainda não dá. */
    fun onSelectPlayers(): GameConfig? {
        val state = _uiState.value
        if (!state.canSelect) return null
        val config = state.toConfig() ?: return null
        val setup = TableSetupPrefs.encode(state)
        settings?.let { repo ->
            val save: suspend () -> Unit = {
                repo.setTableSetup(setup)
                repo.setLastMode(config.mode)
            }
            if (writes != null) writes.enqueue { save() } else viewModelScope.launch { safely { save() } }
        }
        return config
    }
}

/**
 * Última configuração da mesa gravada (docs/telas.md 3.2): modo, nível, aposta, regras e alvo. Ao carregar, o que ficou
 * inválido é ajustado sem aviso (nunca falha).
 */
object TableSetupPrefs {
    fun encode(state: TableSetupUiState): TableSetup = TableSetup(
        mode = state.mode,
        difficultyId = state.difficulty.id,
        rules = state.rulesChoice.name,
        stake = state.stake,
        target = state.targetText,
        skillBoosters = state.skillBoosters,
    )

    /**
     * [state] com a configuração [setup] aplicada e ajustada: aposta na tabela do nível (a mais próxima) e que o [balance]
     * cobre, "Personalizada" só com regras salvas e pontuação-alvo inválida volta ao padrão.
     */
    fun apply(state: TableSetupUiState, setup: TableSetup?, hasCustomRules: Boolean, balance: Int?): TableSetupUiState {
        setup ?: return state.copy(stake = fitStake(state.difficulty.level, state.stake, balance))
        val difficulty = Difficulty.entries.firstOrNull { it.id == setup.difficultyId } ?: state.difficulty
        val rules = RulesChoice.entries.firstOrNull { it.name == setup.rules }
            ?.takeIf { it != RulesChoice.CUSTOM || hasCustomRules } ?: RulesChoice.CLASSICA
        val target = setup.target.takeIf { parseTargetScore(it) != null } ?: state.targetText
        return state.copy(
            mode = setup.mode,
            difficulty = difficulty,
            rulesChoice = rules,
            // §14.6 aposta que não existe na tabela do nível vira o valor mais próximo (empate: o menor)
            stake = fitStake(difficulty.level, setup.stake, balance),
            targetText = target,
            skillBoosters = setup.skillBoosters,
        )
    }

    /** §14.6 a aposta [stake] ajustada à tabela do [level] e ao saldo (sem saldo lido, só à tabela). */
    fun fitStake(level: Int, stake: Int, balance: Int?): Int {
        val index = Coins.stakeIndexOf(level, stake)
        return if (balance == null) Coins.stakesFor(level)[index] else Coins.fitStake(level, index, balance)
    }
}

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

/** Editor das regras personalizadas do jogador (§14.4), aberto pelo Perfil ou pela montagem da mesa. */
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

/** §14.6 índice do nível dos robôs na tabela de apostas: 0 = Fácil (Iniciante), 1 = Médio (Maduro), 2 = Difícil (Sênior). */
val Difficulty.level: Int get() = ordinal
