package com.gtranca.ui.tables

import com.gtranca.ui.tour.TourButton
import com.gtranca.ui.tour.TourHost
import com.gtranca.ui.tour.Tours
import com.gtranca.ui.tour.tourTarget
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import com.gtranca.game.BotLevel
import com.gtranca.ui.persona.BotMotto
import com.gtranca.ai.Difficulty
import com.gtranca.engine.Coins
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Persona
import com.gtranca.ui.game.plainPoints
import com.gtranca.ui.home.labelRes
import com.gtranca.ui.persona.CharacterPortrait
import com.gtranca.ui.persona.LevelStars
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.Pose
import com.gtranca.ui.persona.labelRes
import com.gtranca.ui.persona.pitchRes
import com.gtranca.ui.persona.tagRes
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GChoiceChip
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing

/** Moldura das telas de mesa: fundo, título, conteúdo rolável e barra de botões fixa embaixo. */
@Composable
internal fun TableFrame(
    title: String,
    tag: String,
    bottom: @Composable RowScope.() -> Unit,
    titleExtra: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GBackground(Modifier.fillMaxSize().testTag(tag)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (titleExtra == null) {
                    GTitle(title, Modifier.semantics { heading() })
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GTitle(title, Modifier.weight(1f).semantics { heading() })
                        titleExtra()
                    }
                }
                content()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                content = bottom,
            )
        }
    }
}

@Composable
internal fun RowScope.BottomButton(text: String, tag: String, kind: GButtonKind, enabled: Boolean = true, weight: Float = 1f, onClick: () -> Unit) {
    GButton(onClick, Modifier.weight(weight).heightIn(min = 52.dp).testTag(tag).tourTarget(tag), enabled = enabled, kind = kind) {
        // O texto fica numa linha só: com fonte grande a letra diminui, nunca quebra.
        com.gtranca.ui.theme.FitText(text, MaterialTheme.typography.titleMedium, minFontSp = 10)
    }
}

@Composable
fun modeLabel(mode: GameMode): String =
    stringResource(if (mode == GameMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual)

@Composable
fun rulesName(rules: RuleSet): String = when (rules.id) {
    RuleSet.CLASSICA.id, RuleSet.PAULISTA.id -> rules.name
    else -> stringResource(R.string.rules_custom)
}

// ---------- Iniciar novo jogo (opções da mesa) ----------

/** §16 o botão de Wi-Fi do jogo: o estado (a configuração do Perfil) e o toque, que liga (passando pelas permissões) ou desliga. */
class WifiControl(val on: Boolean, val toggle: () -> Unit)

/**
 * §14.4 *Iniciar novo jogo* (docs/telas.md 3.2): as opções da mesa e, com o Wi-Fi do jogo ligado, a lista (só informativa)
 * dos jogadores por perto que estão recrutando. "Selecionar jogadores" grava a configuração e abre a tela 3.3.
 */
@Composable
fun TablesScreen(
    viewModel: TablesViewModel,
    recruiters: List<com.gtranca.net.PresenceAdvert>,
    wifi: WifiControl?,
    onSelectPlayers: (GameConfig) -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // Tour guiado (docs/telas.md 3.16): sozinho na primeira vez; o ícone ao lado do título o mostra de novo.
    TourHost(Tours.TABLES, Tours.tablesSteps, autoStart = true) { startTour ->
    TableFrame(
        stringResource(R.string.tables_title),
        "tables-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "tables-back", GButtonKind.Secondary, weight = 0.7f, onClick = onBack)
            BottomButton(stringResource(R.string.tables_select_players), "select-players", GButtonKind.Primary, enabled = state.canSelect, weight = 1.3f) {
                viewModel.onSelectPlayers()?.let(onSelectPlayers)
            }
        },
        titleExtra = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TourButton(startTour)
                wifi?.let { WifiButton(it.on, it.toggle) }
            }
        },
    ) {
        if (wifi?.on == true) RecruitersList(recruiters)
        TableOptions(state, viewModel, onCustomize, onShowDetails = { showDetails = true })
    }
    }
    if (showDetails) {
        RulesDetailsDialog(rulesName(state.rules), state.rules, state.targetScore ?: state.rules.targetScore) { showDetails = false }
    }
}

/** §16 botão do Wi-Fi do jogo (3.2): reflete e grava a configuração do Perfil; aceso, o jogador pode ser chamado e chamar. */
@Composable
fun WifiButton(on: Boolean, onToggle: () -> Unit, tag: String = "tables-wifi") {
    val description = stringResource(if (on) R.string.mp_wifi_on_desc else R.string.mp_wifi_off_desc)
    val shape = RoundedCornerShape(Corner.sm)
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (on) GColors.RoyalBlue else androidx.compose.ui.graphics.Color.Transparent, shape)
            .border(1.dp, GColors.Gold.copy(alpha = if (on) 0.9f else 0.5f), shape)
            .clickable(onClickLabel = description, role = Role.Switch, onClick = onToggle)
            .semantics { contentDescription = description }
            .testTag(tag)
            .tourTarget(tag),
        contentAlignment = Alignment.Center,
    ) { com.gtranca.ui.theme.WifiIcon(if (on) GColors.Gold else GColors.Steel, crossed = !on, size = 28.dp) }
}

/**
 * §16.2 "Jogadores recrutando": os outros jogadores que estão na *Seleção de jogadores* (sem o próprio), só informativa:
 * tocar não faz nada. A foto aparece como inicial (limitação do `P2P_STAR`).
 */
@Composable
private fun RecruitersList(recruiters: List<com.gtranca.net.PresenceAdvert>) {
    Column(Modifier.fillMaxWidth().testTag("recruiters").tourTarget("recruiters"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.tables_recruiters_title), style = MaterialTheme.typography.titleSmall, color = GColors.Champagne)
        if (recruiters.isEmpty()) {
            Text(
                stringResource(R.string.tables_recruiters_empty),
                Modifier.testTag("recruiters-empty"),
                style = MaterialTheme.typography.bodySmall,
                color = GColors.Steel,
            )
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                recruiters.forEach { who ->
                    val persona = Persona(who.name, color = who.key.hashCode().mod(Persona.COLORS))
                    Column(
                        Modifier.width(64.dp).testTag("recruiter-${who.key}")
                            .semantics(mergeDescendants = true) { contentDescription = who.name },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PersonaAvatar(persona, 44.dp)
                        Text(who.name, style = MaterialTheme.typography.bodySmall, color = GColors.Champagne, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** §14.4 quadro de opções: modo, dificuldade dos robôs, aposta, regras (com "Ver detalhes da regra") e pontuação-alvo. */
@Composable
private fun TableOptions(state: TableSetupUiState, viewModel: TablesViewModel, onCustomize: () -> Unit, onShowDetails: () -> Unit) {
    GPanel(Modifier.fillMaxWidth().tourTarget("table-options-panel")) {
        Section(stringResource(R.string.home_mode)) {
            ModeChoice(stringResource(R.string.mode_individual), state.mode == GameMode.INDIVIDUAL, "mode-individual") { viewModel.onModeChange(GameMode.INDIVIDUAL) }
            ModeChoice(stringResource(R.string.mode_duplas_short), state.mode == GameMode.DUPLAS, "mode-duplas") { viewModel.onModeChange(GameMode.DUPLAS) }
        }
        Section(stringResource(R.string.tables_difficulty)) {
            Difficulty.entries.forEach { difficulty ->
                Chip(stringResource(difficulty.labelRes()), state.difficulty == difficulty, "difficulty-${difficulty.id}") {
                    viewModel.onDifficultyChange(difficulty)
                }
            }
        }
        Section(stringResource(R.string.create_stake)) {
            // §14.6 três valores por nível, cada um com o saco de dinheiro; acima do saldo, desabilitado.
            state.stakes.forEach { stake ->
                GChoiceChip(
                    state.stake == stake,
                    { viewModel.onStakeChange(stake) },
                    Modifier.weight(1f).testTag("stake-$stake"),
                    enabled = Coins.canJoin(state.balance, stake),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Image(painterResource(R.drawable.coin_bag_small), null, Modifier.size(20.dp))
                        Text(
                            stake.toString(),
                            color = if (state.stake == stake) GColors.Ivory else GColors.Steel,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        if (!state.canAfford) {
            Text(stringResource(R.string.stake_insufficient), Modifier.testTag("tables-insufficient"), style = MaterialTheme.typography.bodyMedium, color = GColors.Ruby)
        }
        Section(stringResource(R.string.create_rules)) {
            Chip(RuleSet.CLASSICA.name, state.rulesChoice == RulesChoice.CLASSICA, "rules-classic") { viewModel.onRulesChange(RulesChoice.CLASSICA) }
            Chip(RuleSet.PAULISTA.name, state.rulesChoice == RulesChoice.PAULISTA, "rules-paulista") { viewModel.onRulesChange(RulesChoice.PAULISTA) }
            // §14.4 "Personalizada" sempre abre o editor; ao salvar, volta com ela escolhida.
            Chip(stringResource(R.string.rules_custom), state.rulesChoice == RulesChoice.CUSTOM, "rules-custom", onCustomize)
        }
        // §14.4 o resumo das regras não fica na tela: este botão discreto o abre num modal.
        androidx.compose.material3.TextButton(onShowDetails, Modifier.heightIn(min = 48.dp).testTag("rules-details")) {
            Text(stringResource(R.string.rules_view_details), style = MaterialTheme.typography.labelLarge, color = GColors.Gold)
        }
        OutlinedTextField(
            value = state.targetText,
            onValueChange = viewModel::onTargetChange,
            label = { Text(stringResource(R.string.home_target)) },
            isError = state.targetError != null,
            supportingText = when (state.targetError) {
                null -> null
                TargetError.INVALID -> {
                    { Text(stringResource(R.string.home_target_error)) }
                }
                TargetError.TOO_LARGE -> {
                    { Text(stringResource(R.string.home_target_too_large, TableSetupUiState.MAX_TARGET)) }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("target-score"),
        )
    }
}

/** Escolha do modo (Individual / Duplas). */
@Composable
private fun RowScope.ModeChoice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.weight(1f).testTag(tag))
}

/** Modal *Detalhes da regra* (docs/telas.md 4): o resumo das regras da mesa (§14.2) e a pontuação-alvo. */
@Composable
fun RulesDetailsDialog(title: String, rules: RuleSet, targetScore: Int, onClose: () -> Unit) {
    com.gtranca.ui.theme.GDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("rules-details-dialog"),
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { RulesSummary(rules, targetScore) } },
        confirmButton = {
            androidx.compose.material3.TextButton(onClose, Modifier.heightIn(min = 48.dp).testTag("rules-details-close")) {
                Text(stringResource(R.string.rules_details_close))
            }
        },
    )
}

// ---------- Resumo das regras ----------

/** Lista dos parâmetros de regra (§14.2) com o valor de [rules]; os que diferem da Clássica ficam destacados. */
@Composable
fun RulesSummary(rules: RuleSet, targetScore: Int, modifier: Modifier = Modifier) {
    val base = RuleSet.CLASSICA
    GPanel(modifier.fillMaxWidth().testTag("rules-summary")) {
        Text(rulesName(rules), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GColors.Gold)
        if (rules.id != base.id) {
            Text(stringResource(R.string.table_rules_differs), style = MaterialTheme.typography.labelSmall, color = GColors.Steel)
        }
        RuleLine(stringResource(R.string.rule_groups), groupsText(rules.groupRanks), rules.groupRanks != base.groupRanks)
        RuleLine(
            stringResource(R.string.rule_wilds),
            stringResource(if (rules.maxWildsPerMeld == 0) R.string.rule_wilds_none else R.string.rule_wilds_one),
            rules.maxWildsPerMeld != base.maxWildsPerMeld,
        )
        val noWilds = rules.maxWildsPerMeld == 0
        if (noWilds) {
            // §1 sem coringas não há canastra suja: um valor só.
            RuleLine(
                stringResource(R.string.rule_canasta_plain),
                stringResource(R.string.rule_canasta_plain_value, rules.cleanCanastaPoints),
                rules.cleanCanastaPoints != base.cleanCanastaPoints,
            )
        } else {
            RuleLine(
                stringResource(R.string.rule_canasta),
                stringResource(R.string.rule_canasta_value, rules.cleanCanastaPoints, rules.dirtyCanastaPoints),
                rules.cleanCanastaPoints != base.cleanCanastaPoints || rules.dirtyCanastaPoints != base.dirtyCanastaPoints,
            )
        }
        RuleLine(
            stringResource(R.string.rule_stock),
            stringResource(if (rules.mortoBecomesStock) R.string.rule_stock_morto else R.string.rule_stock_end),
            rules.mortoBecomesStock != base.mortoBecomesStock,
        )
        if (!noWilds) RuleLine(
            stringResource(R.string.rule_go_out),
            stringResource(if (rules.goOutRequiresCleanCanasta) R.string.rule_go_out_clean else R.string.rule_go_out_any),
            rules.goOutRequiresCleanCanasta != base.goOutRequiresCleanCanasta,
        )
        RuleLine(
            stringResource(R.string.rule_black_three),
            stringResource(R.string.rule_black_three_value, rules.handBlackThreePoints),
            rules.handBlackThreePoints != base.handBlackThreePoints,
        )
        RuleLine(stringResource(R.string.rule_target), stringResource(R.string.rule_target_value, plainPoints(targetScore)), false)
    }
}

@Composable
private fun groupsText(value: GroupRanks): String = stringResource(
    when (value) {
        GroupRanks.ANY -> R.string.rule_groups_any
        GroupRanks.FACE_AND_ACE -> R.string.rule_groups_face
        GroupRanks.NONE -> R.string.rule_groups_none
    },
)

@Composable
private fun RuleLine(label: String, value: String, differs: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = GColors.OnTable)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (differs) FontWeight.Bold else FontWeight.Normal,
            color = if (differs) GColors.Gold else GColors.Champagne,
        )
    }
}
@Composable
private fun Section(title: String, chips: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = chips)
    }
}

@Composable
private fun RowScope.Chip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.weight(1f).testTag(tag))
}

// ---------- Editor das regras personalizadas ----------

/** §14.2, §14.4 editor das regras personalizadas do jogador; ao salvar, chama [onSaved]. */
@Composable
fun RulesEditorScreen(viewModel: RulesEditorViewModel, onSaved: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    val rules = state.rules
    TableFrame(
        stringResource(R.string.rules_editor_title),
        "rules-editor-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "rules-back", GButtonKind.Secondary, onClick = onBack)
            BottomButton(stringResource(R.string.rules_save), "rules-save", GButtonKind.Primary, enabled = state.canSave, onClick = viewModel::onSave)
        },
    ) {
        Text(stringResource(R.string.rules_editor_hint), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
        GPanel(Modifier.fillMaxWidth()) {
            Section(stringResource(R.string.rules_start_from)) {
                RuleSet.HOUSE.forEach { house ->
                    Chip(house.name, false, "rules-from-${house.id}") { viewModel.onStartFrom(house) }
                }
            }
        }
        GPanel(Modifier.fillMaxWidth()) {
            Section(stringResource(R.string.rule_groups)) {
                Chip(stringResource(R.string.rule_groups_any), rules.groupRanks == GroupRanks.ANY, "groups-any") { viewModel.onGroupRanks(GroupRanks.ANY) }
                Chip(stringResource(R.string.rule_groups_face), rules.groupRanks == GroupRanks.FACE_AND_ACE, "groups-face") { viewModel.onGroupRanks(GroupRanks.FACE_AND_ACE) }
                Chip(stringResource(R.string.rule_groups_none), rules.groupRanks == GroupRanks.NONE, "groups-none") { viewModel.onGroupRanks(GroupRanks.NONE) }
            }
            Section(stringResource(R.string.rule_wilds)) {
                Chip(stringResource(R.string.rule_wilds_one), rules.maxWildsPerMeld == 1, "wilds-1") { viewModel.onMaxWilds(1) }
                Chip(stringResource(R.string.rule_wilds_none), rules.maxWildsPerMeld == 0, "wilds-0") { viewModel.onMaxWilds(0) }
            }
            SwitchLine(stringResource(R.string.rule_stock_switch), rules.mortoBecomesStock, "switch-morto-stock", viewModel::onMortoBecomesStock)
            if (rules.maxWildsPerMeld == 0) {
                // §1 sem coringas toda canastra é igual: um valor só e nenhuma exigência de limpa para bater.
                PointsField(stringResource(R.string.rule_canasta_points), state.cleanText, state.cleanError, "points-clean", viewModel::onCleanText)
            } else {
                SwitchLine(stringResource(R.string.rule_go_out_switch), rules.goOutRequiresCleanCanasta, "switch-go-out-clean", viewModel::onGoOutRequiresClean)
                PointsField(stringResource(R.string.rule_clean_points), state.cleanText, state.cleanError, "points-clean", viewModel::onCleanText)
                PointsField(stringResource(R.string.rule_dirty_points), state.dirtyText, state.dirtyError, "points-dirty", viewModel::onDirtyText)
            }
            PointsField(stringResource(R.string.rule_black_three_points), state.blackThreeText, state.blackThreeError, "points-black-three", viewModel::onBlackThreeText)
        }
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = GColors.OnTable)
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun PointsField(label: String, text: String, error: Boolean, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error,
        supportingText = if (error) {
            { Text(stringResource(R.string.rules_points_error)) }
        } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
