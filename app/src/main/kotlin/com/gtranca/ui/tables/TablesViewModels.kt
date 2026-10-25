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
import com.gtranca.game.BotProfile
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

/** §14.4 um participante marcado na montagem, além do jogador: um jogador próximo (§16.2) ou um personagem (§14.5). */
sealed interface Pick {
    /** Jogador próximo achado pela presença ([key] da presença). */
    data class Near(val key: String) : Pick

    /** Personagem ([BotProfile]). */
    data class Bot(val profile: BotProfile) : Pick
}

/**
 * §16.2 jogador próximo achado (presença: chave e nome) e a foto dele, se já chegou e se o jogador quer ver as fotos
 * ("Ver fotos nas mesas compartilhadas"); `null` = inicial. Só em memória.
 */
data class NearbyPlayer(
    val key: String,
    val name: String,
    val photo: ByteArray? = null,
    /** §16.2 recrutado por **outro** anfitrião: "Recrutado", não selecionável. */
    val recruitedElsewhere: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is NearbyPlayer && key == other.key && name == other.name && photo.contentEquals(other.photo) &&
            recruitedElsewhere == other.recruitedElsewhere

    override fun hashCode(): Int = 31 * (31 * key.hashCode() + name.hashCode()) + photo.contentHashCode() + recruitedElsewhere.hashCode()
}

/** §16.2 o recrutamento imediato visto pela montagem: marcar envia o convite na hora; desmarcar cancela ou tira da mesa. */
interface Recruiter {
    /** `false` = não deu para marcar (recrutado por outro, limite, este aparelho recrutado). */
    suspend fun mark(player: NearbyPlayer): Boolean

    suspend fun unmark(key: String)
}

/** Avisos breves da montagem (§16.2). */
enum class TablesNotice { RECRUITED_ELSEWHERE, MARK_FAILED }

/**
 * Estado da montagem da mesa (§14.4, docs/telas.md 3.2): participantes marcados (na ordem da escolha), modo, dificuldade
 * dos robôs (padrão médio), regras, aposta e pontuação-alvo (padrão 3000).
 */
data class TableSetupUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val rulesChoice: RulesChoice = RulesChoice.CLASSICA,
    /** Regras personalizadas do jogador (§14.4). */
    val customRules: RuleSet = Tables.defaultCustomRules("Minhas regras"),
    val targetText: String = RuleSet.DEFAULT.targetScore.toString(),
    /** Já leu a última montagem e as regras personalizadas. */
    val loaded: Boolean = true,
    /** §14.6 aposta escolhida e saldo da carteira. */
    val stake: Int = Coins.DEFAULT_STAKE,
    val balance: Int = Coins.INITIAL_BALANCE,
    /** §14.4 outros participantes marcados, na ordem da escolha (no máximo [MAX_PICKS]). */
    val picks: List<Pick> = emptyList(),
    /** §16.2 jogadores próximos achados agora (só com o Wi-Fi do jogo ligado). */
    val nearby: List<NearbyPlayer> = emptyList(),
    /** §16.2 jogadores próximos marcados que já aceitaram o convite (os outros marcados aparecem "aguardando"). */
    val accepted: Set<String> = emptySet(),
) {
    /** §14.4 participantes contando o jogador (sempre marcado). */
    val participants: Int get() = 1 + picks.size

    /** §14.4 com 3 ou 4 participantes o modo é duplas, sem escolha. */
    val modeLocked: Boolean get() = participants >= 3

    /** O modo que vale: [mode], ou duplas com 3 ou 4 participantes. */
    val effectiveMode: GameMode get() = if (modeLocked) GameMode.DUPLAS else mode

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

    val canChoose: Boolean get() = targetScore != null && loaded && canAfford

    fun isPicked(pick: Pick): Boolean = pick in picks

    fun toConfig(): GameConfig? = targetScore?.let { GameConfig(effectiveMode, difficulty, it, rules, stake) }

    companion object {
        const val MAX_TARGET: Int = Int.MAX_VALUE

        /** §14.4 no máximo 3 participantes além do jogador. */
        const val MAX_PICKS: Int = 3
    }
}

/** §14.4 mesa montada: a configuração, os outros participantes (na ordem da escolha) e os jogadores próximos marcados. */
data class ChosenTable(val config: GameConfig, val picks: List<Pick>, val nearby: List<NearbyPlayer>)

/**
 * "Mesas disponíveis" como montagem da mesa (§14.4, docs/telas.md 3.2): sempre preenchida com a última montagem gravada,
 * ajustada ao que vale agora; "Escolher posições" grava a montagem e devolve a mesa.
 */
class TablesViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
    wallet: WalletRepository? = null,
) : ViewModel() {
    /** §16.2 recrutamento imediato (com o Wi-Fi do jogo ligado); `null` = sem rede. */
    @Volatile
    var recruiter: Recruiter? = null

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<TablesNotice>(extraBufferCapacity = 8)

    /** Avisos breves (não deu para marcar o jogador próximo). */
    val notices: kotlinx.coroutines.flow.SharedFlow<TablesNotice> = _notices

    /**
     * §16.2 os marcados do recrutamento (pendentes e aceitos): quem recusou, saiu ou foi por saldo sai da marcação; os
     * aceitos ficam marcados como tal.
     */
    fun onRecruits(recruits: List<com.gtranca.net.Recruit>) = _uiState.update { state ->
        val keys = recruits.map { it.key }.toSet()
        state.copy(
            picks = state.picks.filter { it !is Pick.Near || it.key in keys },
            accepted = recruits.filter { it.accepted }.map { it.key }.toSet(),
        )
    }

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

    /** O jogador já mexeu na montagem nesta tela: a gravada não a sobrescreve. */
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

    /** §14.4 marca ou desmarca um participante; o 4º além do jogador não marca. */
    fun onToggle(pick: Pick) {
        val state = _uiState.value
        val near = pick as? Pick.Near
        val rec = recruiter
        if (near == null || rec == null) return toggleLocal(pick)
        if (pick in state.picks) {
            // §16.2 desmarcar cancela o convite (ou tira da mesa quem já aceitou).
            edit { s -> s.copy(picks = s.picks - pick) }
            viewModelScope.launch { safely { rec.unmark(near.key) } }
            return
        }
        val player = state.nearby.firstOrNull { it.key == near.key } ?: return
        if (state.picks.size >= TableSetupUiState.MAX_PICKS) return
        if (player.recruitedElsewhere) {
            _notices.tryEmit(TablesNotice.RECRUITED_ELSEWHERE)
            return
        }
        // §16.2 marcar envia o convite na hora; recusado pelo recrutamento, não marca (com o motivo).
        viewModelScope.launch {
            val ok = safely { rec.mark(player) } ?: false
            if (ok) edit { s -> if (pick in s.picks || s.picks.size >= TableSetupUiState.MAX_PICKS) s else s.copy(picks = s.picks + pick) }
            else _notices.tryEmit(TablesNotice.MARK_FAILED)
        }
    }

    private fun toggleLocal(pick: Pick) = edit { state ->
        when {
            pick in state.picks -> state.copy(picks = state.picks - pick)
            state.picks.size >= TableSetupUiState.MAX_PICKS -> state
            pick is Pick.Near && state.nearby.none { it.key == pick.key } -> state
            else -> state.copy(picks = state.picks + pick)
        }
    }

    /**
     * §16.2 jogadores próximos achados agora. Com o recrutamento ativo, sumir da busca por um instante **não** desmarca
     * ninguém: quem decide é o recrutamento (conexão caída, prazo de reingresso, "fora do alcance"), que avisa por
     * [onRecruits]. Sem recrutamento, quem sumiu deixa de estar marcado.
     */
    fun onNearby(players: List<NearbyPlayer>) {
        val keys = players.map { it.key }.toSet()
        val live = recruiter != null
        _uiState.update { state ->
            // Marcado que sumiu da lista continua na grade (com o nome que já tinha) enquanto o recrutamento o mantém.
            val kept = if (live) state.nearby.filter { old -> old.key !in keys && state.picks.any { it is Pick.Near && it.key == old.key } } else emptyList()
            state.copy(nearby = players + kept, picks = if (live) state.picks else state.picks.filter { it !is Pick.Near || it.key in keys })
        }
    }

    fun onModeChange(mode: GameMode) = edit { if (it.modeLocked) it else it.copy(mode = mode) }

    fun onDifficultyChange(difficulty: Difficulty) = edit { it.copy(difficulty = difficulty) }

    fun onRulesChange(choice: RulesChoice) = edit { it.copy(rulesChoice = choice) }

    fun onTargetChange(text: String) = edit { it.copy(targetText = text) }

    /** §14.6 aposta da mesa (uma das [Coins.STAKES] que o saldo cobre; outro valor é ignorado). */
    fun onStakeChange(stake: Int) = edit { if (stake in Coins.STAKES && Coins.canJoin(it.balance, stake)) it.copy(stake = stake) else it }

    /** "Escolher posições": grava a montagem (sem os jogadores próximos) e devolve a mesa, ou `null` se ainda não dá. */
    fun onChooseSeats(): ChosenTable? {
        val state = _uiState.value
        if (!state.canChoose) return null
        val config = state.toConfig() ?: return null
        val setup = TableSetupPrefs.encode(state)
        settings?.let { repo ->
            val save: suspend () -> Unit = {
                repo.setTableSetup(setup)
                repo.setLastMode(config.mode)
            }
            if (writes != null) writes.enqueue { save() } else viewModelScope.launch { safely { save() } }
        }
        val near = state.picks.filterIsInstance<Pick.Near>().mapNotNull { p -> state.nearby.firstOrNull { it.key == p.key } }
        return ChosenTable(config, state.picks, near)
    }
}

/**
 * Última montagem da mesa gravada (docs/telas.md 3.2): modo, nível, regras, aposta, alvo e os robôs marcados. Ao carregar,
 * o que ficou inválido é ajustado sem aviso (nunca falha).
 */
object TableSetupPrefs {
    fun encode(state: TableSetupUiState): TableSetup = TableSetup(
        mode = state.effectiveMode,
        difficultyId = state.difficulty.id,
        rules = state.rulesChoice.name,
        stake = state.stake,
        target = state.targetText,
        robots = state.picks.filterIsInstance<Pick.Bot>().map { it.profile.code },
    )

    /**
     * [state] com a montagem [setup] aplicada e ajustada: aposta que o [balance] cobre, "Personalizada" só com regras
     * salvas, pontuação-alvo inválida volta ao padrão e Individual com mais de 2 marcados (contando o jogador) vira Duplas.
     */
    fun apply(state: TableSetupUiState, setup: TableSetup?, hasCustomRules: Boolean, balance: Int?): TableSetupUiState {
        setup ?: return state.copy(stake = fitStake(state.stake, balance))
        val difficulty = Difficulty.entries.firstOrNull { it.id == setup.difficultyId } ?: state.difficulty
        val rules = RulesChoice.entries.firstOrNull { it.name == setup.rules }
            ?.takeIf { it != RulesChoice.CUSTOM || hasCustomRules } ?: RulesChoice.CLASSICA
        val stake = setup.stake.takeIf { it in Coins.STAKES } ?: state.stake
        val target = setup.target.takeIf { parseTargetScore(it) != null } ?: state.targetText
        val robots = setup.robots.mapNotNull { code -> BotProfile.entries.firstOrNull { it.code == code } }.distinct()
            .take(TableSetupUiState.MAX_PICKS).map { Pick.Bot(it) }
        val mode = if (setup.mode == GameMode.INDIVIDUAL && 1 + robots.size > 2) GameMode.DUPLAS else setup.mode
        return state.copy(
            mode = mode,
            difficulty = difficulty,
            rulesChoice = rules,
            stake = fitStake(stake, balance),
            targetText = target,
            picks = robots,
        )
    }

    /** A aposta [stake] se o saldo a cobre; senão a maior que ele cobre (ou a menor, sem saldo). */
    fun fitStake(stake: Int, balance: Int?): Int {
        if (balance == null || stake <= balance) return stake
        return Coins.STAKES.filter { it <= balance }.maxOrNull() ?: Coins.STAKES.min()
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
