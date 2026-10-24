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
import com.gtranca.game.BotCharacter
import com.gtranca.game.BotProfile
import androidx.compose.foundation.selection.toggleable
import com.gtranca.ui.game.plainPoints
import com.gtranca.ui.home.ModeCard
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
private fun TableFrame(
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
private fun RowScope.BottomButton(text: String, tag: String, kind: GButtonKind, enabled: Boolean = true, onClick: () -> Unit) {
    GButton(onClick, Modifier.weight(1f).heightIn(min = 52.dp).testTag(tag).tourTarget(tag), enabled = enabled, kind = kind) {
        Text(text, style = MaterialTheme.typography.titleMedium)
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

// ---------- Mesas disponíveis (montagem da mesa) ----------

/** §16 a parte de rede de "Mesas disponíveis": o Wi-Fi do jogo (Perfil) e as mesas de outros anfitriões por perto. */
class TablesMultiplayer(
    val wifiOn: Boolean,
    val onToggle: () -> Unit,
    val tables: List<com.gtranca.net.TableAdvert>,
    val onOpen: (com.gtranca.net.TableAdvert) -> Unit,
)

/**
 * §14.4 "Mesas disponíveis" (docs/telas.md 3.2): a montagem da mesa — participantes (o jogador, os jogadores próximos e os
 * 4 robôs), as mesas de outros anfitriões por perto (pedido de entrada) e as opções; "Escolher posições" abre a escolha de
 * posições ([onChooseSeats]).
 */
@Composable
fun TablesScreen(
    viewModel: TablesViewModel,
    self: Persona,
    onChooseSeats: (ChosenTable) -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit,
    multiplayer: TablesMultiplayer? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Tour guiado (docs/telas.md 3.16): sozinho na primeira vez; o ícone ao lado do título o mostra de novo.
    TourHost(Tours.TABLES, Tours.tablesSteps, autoStart = true) { startTour ->
    TableFrame(
        stringResource(R.string.tables_title),
        "tables-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "tables-back", GButtonKind.Secondary, onClick = onBack)
            BottomButton(stringResource(R.string.tables_choose_seats), "choose-seats", GButtonKind.Primary, enabled = state.canChoose) {
                viewModel.onChooseSeats()?.let(onChooseSeats)
            }
        },
        titleExtra = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TourButton(startTour)
                multiplayer?.let { mp -> WifiButton(mp.wifiOn, mp.onToggle) }
            }
        },
    ) {
        val wifiOn = multiplayer?.wifiOn == true
        ParticipantsPill(state, self, wifiOn, viewModel::onToggle)
        if (multiplayer != null && wifiOn) {
            // §16.2 mesas de outros anfitriões por perto (aparecem e somem conforme a busca); tocar pede a entrada.
            multiplayer.tables.forEach { advert -> MultiplayerTableCard(advert) { multiplayer.onOpen(advert) } }
        }
        TableOptions(state, viewModel, onCustomize)
        RulesSummary(state.rules, state.targetScore ?: state.rules.targetScore)
    }
    }
}

/** §16 botão do Wi-Fi do jogo (3.2): reflete e grava a configuração do Perfil; aceso, procurando jogadores e mesas por perto. */
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
 * §14.4 pílula de escolha dos participantes (docs/telas.md 3.2): grade de avatares, até 6 por linha — o jogador (sempre
 * o primeiro e marcado), os jogadores próximos (ou "Recrutando", só com o Wi-Fi ligado) e os 4 robôs; até 3 marcados
 * além do jogador; mostra até 4 linhas e rola a partir da 5ª.
 */
@Composable
private fun ParticipantsPill(state: TableSetupUiState, self: Persona, wifiOn: Boolean, onToggle: (Pick) -> Unit) {
    val shape = RoundedCornerShape(Corner.md)
    val level = BotLevel.of(state.difficulty)
    val tiles = buildList<@Composable RowScope.() -> Unit> {
        add { ParticipantTile(self, self.firstName, checked = true, enabled = false, tag = "participant-self") {} }
        if (wifiOn) {
            if (state.nearby.isEmpty()) add { RecruitingTile() }
            state.nearby.forEach { player ->
                val pick = Pick.Near(player.key)
                add {
                    ParticipantTile(
                        Persona(player.name, color = player.key.hashCode().mod(Persona.COLORS), photoBytes = player.photo),
                        player.name,
                        checked = state.isPicked(pick),
                        enabled = true,
                        tag = "participant-near-${player.key}",
                    ) { onToggle(pick) }
                }
            }
        }
        BotProfile.entries.forEach { profile ->
            val pick = Pick.Bot(profile)
            add {
                ParticipantTile(
                    Persona(profile.botName, "", BotCharacter(profile, level)),
                    profile.botName,
                    checked = state.isPicked(pick),
                    enabled = true,
                    tag = "participant-bot-${profile.code}",
                ) { onToggle(pick) }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GColors.Indigo, shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.6f), shape)
            .heightIn(max = PARTICIPANT_TILE * PARTICIPANT_ROWS_VISIBLE + Spacing.sm * 2)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.sm)
            .testTag("participants")
            .tourTarget("participants"),
    ) {
        tiles.chunked(PARTICIPANTS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
                row.forEach { it() }
                repeat(PARTICIPANTS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Um avatar da pílula de participantes: imagem em círculo e o nome embaixo. Marcado: aro dourado grosso e um ✓ dourado
 * no canto; desmarcado: aro fino e a imagem um pouco apagada.
 */
@Composable
private fun RowScope.ParticipantTile(avatar: Persona, name: String, checked: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = PARTICIPANT_TILE)
            .clip(RoundedCornerShape(Corner.sm))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(vertical = Spacing.xs)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(PARTICIPANT_AVATAR + 8.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(PARTICIPANT_AVATAR + 6.dp)
                    .border(if (checked) 3.dp else 1.dp, GColors.Gold.copy(alpha = if (checked) 1f else 0.4f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(PARTICIPANT_AVATAR).then(if (checked) Modifier else Modifier.alpha(0.55f))) {
                    PersonaAvatar(avatar, PARTICIPANT_AVATAR)
                }
            }
            if (checked) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .background(GColors.Gold, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = GColors.Indigo, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(
            name,
            Modifier.padding(horizontal = 2.dp),
            color = if (checked) GColors.Champagne else GColors.Steel,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Recrutando" (Wi-Fi ligado e ninguém por perto): círculo girando no lugar do avatar, logo depois do jogador. */
@Composable
private fun RowScope.RecruitingTile() {
    Column(
        Modifier.weight(1f).heightIn(min = PARTICIPANT_TILE).padding(vertical = Spacing.xs).testTag("participant-recruiting"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(PARTICIPANT_AVATAR + 8.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(PARTICIPANT_AVATAR - 8.dp), color = GColors.Gold, strokeWidth = 2.dp)
        }
        Text(
            stringResource(R.string.tables_recruiting),
            color = GColors.Steel,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Diâmetro do avatar na grade de participantes. */
private val PARTICIPANT_AVATAR = 42.dp

/** Altura de cada linha da grade (avatar com aro + nome). */
private val PARTICIPANT_TILE = 76.dp

/** §14.4 até 6 avatares por linha (o jogador conta); em 360 dp cada coluna fica com ~53 dp. */
private const val PARTICIPANTS_PER_ROW = 6

/** §14.4 a pílula mostra até 4 linhas e rola a partir da 5ª. */
private const val PARTICIPANT_ROWS_VISIBLE = 4

/** §14.4 opções da mesa (as da antiga "Criar mesa"): modo, dificuldade dos robôs, aposta, regras e pontuação-alvo. */
@Composable
private fun TableOptions(state: TableSetupUiState, viewModel: TablesViewModel, onCustomize: () -> Unit) {
    GPanel(Modifier.fillMaxWidth().tourTarget("table-options-panel")) {
        Section(stringResource(R.string.home_mode)) {
            // §14.4 com 3 ou 4 participantes o modo é duplas (Individual desabilitado).
            ModeChoice(stringResource(R.string.mode_individual), state.effectiveMode == GameMode.INDIVIDUAL, !state.modeLocked, "mode-individual") {
                viewModel.onModeChange(GameMode.INDIVIDUAL)
            }
            ModeChoice(stringResource(R.string.mode_duplas_short), state.effectiveMode == GameMode.DUPLAS, true, "mode-duplas") {
                viewModel.onModeChange(GameMode.DUPLAS)
            }
        }
        Section(stringResource(R.string.tables_difficulty)) {
            Difficulty.entries.forEach { difficulty ->
                Chip(stringResource(difficulty.labelRes()), state.difficulty == difficulty, "difficulty-${difficulty.id}") {
                    viewModel.onDifficultyChange(difficulty)
                }
            }
        }
        Section(stringResource(R.string.create_stake)) {
            // §14.6 apostas acima do saldo ficam desabilitadas (o saldo fica só no topo).
            Coins.STAKES.forEach { stake ->
                GChoiceChip(
                    stake.toString(),
                    state.stake == stake,
                    { viewModel.onStakeChange(stake) },
                    Modifier.weight(1f).testTag("stake-$stake"),
                    enabled = Coins.canJoin(state.balance, stake),
                )
            }
        }
        if (!state.canAfford) {
            Text(stringResource(R.string.stake_insufficient), Modifier.testTag("tables-insufficient"), style = MaterialTheme.typography.bodyMedium, color = GColors.Ruby)
        }
        Section(stringResource(R.string.create_rules)) {
            Chip(RuleSet.CLASSICA.name, state.rulesChoice == RulesChoice.CLASSICA, "rules-classica") { viewModel.onRulesChange(RulesChoice.CLASSICA) }
            Chip(RuleSet.PAULISTA.name, state.rulesChoice == RulesChoice.PAULISTA, "rules-paulista") { viewModel.onRulesChange(RulesChoice.PAULISTA) }
            // §14.4 "Personalizada" sempre abre o editor; ao salvar, volta com ela escolhida.
            Chip(stringResource(R.string.rules_custom), state.rulesChoice == RulesChoice.CUSTOM, "rules-custom", onCustomize)
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
            modifier = Modifier.fillMaxWidth().testTag("target-field"),
        )
    }
}

/** Escolha do modo (Individual / Duplas), desabilitável. */
@Composable
private fun RowScope.ModeChoice(text: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.weight(1f).testTag(tag), enabled = enabled)
}

/**
 * §16.2 card de mesa de outro anfitrião (docs/telas.md 3.2): banner "Multi player", "Mesa de <anfitrião>" e a marca
 * "jogo em andamento"; tocar envia o pedido de entrada. Regras, aposta e nível aparecem na escolha de posições.
 */
@Composable
private fun MultiplayerTableCard(advert: com.gtranca.net.TableAdvert, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.md)
    val id = advert.tableId
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GColors.Indigo, shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.8f), shape)
            .clickable(onClick = onClick)
            .padding(Spacing.md)
            .testTag("table-$id"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        MultiBanner(Modifier.width(96.dp).testTag("table-$id-multi-banner"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.tables_host, advert.hostName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GColors.Champagne, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (advert.inProgress) {
                Text(
                    stringResource(R.string.mp_in_progress),
                    Modifier
                        .background(GColors.Bordeaux, RoundedCornerShape(Corner.sm))
                        .padding(horizontal = Spacing.sm, vertical = 2.dp)
                        .testTag("table-$id-in-progress"),
                    style = MaterialTheme.typography.labelMedium,
                    color = GColors.Ivory,
                )
            }
        }
    }
}

/** §16 banner animado "Multi" / "player" (o brilho dourado pulsa; parado sem animações do sistema). */
@Composable
private fun MultiBanner(modifier: Modifier) {
    val motion = com.gtranca.ui.theme.rememberMotionEnabled()
    val glow = if (motion) {
        val value by androidx.compose.animation.core.rememberInfiniteTransition(label = "multi").animateFloat(
            0.55f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1200), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "glow",
        )
        value
    } else {
        1f
    }
    val shape = RoundedCornerShape(Corner.sm)
    Column(
        modifier
            .clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(GColors.RoyalStart, GColors.Midnight)), shape)
            .border(2.dp, GColors.Gold.copy(alpha = glow), shape)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.mp_banner_multi), color = GColors.Gold.copy(alpha = glow), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.mp_banner_player), color = GColors.Champagne, style = MaterialTheme.typography.titleSmall)
    }
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
