package com.gtranca.ui.tables

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
import com.gtranca.ui.theme.RecycleIcon
import com.gtranca.ui.theme.RulesIcon
import com.gtranca.ai.Difficulty
import com.gtranca.engine.Coins
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Persona
import com.gtranca.game.TableOffer
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
    GButton(onClick, Modifier.weight(1f).heightIn(min = 52.dp).testTag(tag), enabled = enabled, kind = kind) {
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

// ---------- Mesas disponíveis ----------

/** §16 a parte multijogador de "Mesas disponíveis": o botão de Wi-Fi e as mesas achadas por perto. */
class TablesMultiplayer(
    val wifiOn: Boolean,
    val onToggle: () -> Unit,
    val tables: List<com.gtranca.net.TableAdvert>,
    val onOpen: (com.gtranca.net.TableAdvert) -> Unit,
)

/** §14.4 "Novo jogo": mesas abertas pelos jogadores virtuais + "Criar mesa"; §16 com o Wi-Fi, as mesas por perto. */
@Composable
fun TablesScreen(
    viewModel: TablesViewModel,
    onOpen: (TableOffer) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    multiplayer: TablesMultiplayer? = null,
) {
    val tables by viewModel.tables.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    TableFrame(
        stringResource(R.string.tables_title),
        "tables-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "tables-back", GButtonKind.Secondary, onClick = onBack)
            BottomButton(stringResource(R.string.tables_create), "tables-create", GButtonKind.Primary, onClick = onCreate)
        },
        titleExtra = multiplayer?.let { mp -> { WifiButton(mp.wifiOn, mp.onToggle) } },
    ) {
        Text(stringResource(R.string.tables_subtitle), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
        Text(stringResource(R.string.coins_balance, balance), Modifier.testTag("coins-balance"), style = MaterialTheme.typography.titleMedium, color = GColors.Gold)
        if (multiplayer != null && multiplayer.wifiOn) {
            // §16.2 mesas abertas por perto, acrescentadas à lista (aparecem e somem conforme a busca).
            if (multiplayer.tables.isEmpty()) {
                Text(stringResource(R.string.mp_searching), Modifier.testTag("tables-searching"), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
            }
            multiplayer.tables.forEach { advert -> MultiplayerTableCard(advert) { multiplayer.onOpen(advert) } }
        }
        tables.forEach { table -> TableCard(table, balance) { onOpen(table) } }
    }
}

/** §16 botão de Wi-Fi (3.2): ligado, procura mesas por perto; desligado, nada de rede. */
@Composable
private fun WifiButton(on: Boolean, onToggle: () -> Unit) {
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
            .testTag("tables-wifi"),
        contentAlignment = Alignment.Center,
    ) { com.gtranca.ui.theme.WifiIcon(if (on) GColors.Gold else GColors.Steel, crossed = !on, size = 28.dp) }
}

/**
 * §16 card de mesa multijogador, primeira versão (docs/telas.md 3.2): nome da mesa, anfitrião e "jogo em andamento"; no
 * lugar do retrato, o banner "Multi player". Regras, aposta e posições aparecem ao entrar (o anúncio é curto).
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
            Text(advert.tableName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GColors.Champagne, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.mp_table_host, advert.hostName), style = MaterialTheme.typography.bodyMedium, color = GColors.Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(stringResource(R.string.mp_table_details_inside), style = MaterialTheme.typography.bodySmall, color = GColors.Steel)
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

@Composable
private fun TableCard(table: TableOffer, balance: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.md)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GColors.Indigo, shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(Spacing.md)
            .testTag("table-${table.id}"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // §14.5 apresentação do personagem: retrato animado, nível e característica (no individual; em duplas, os três
        // personagens da mesa aparecem em retratos pequenos logo abaixo).
        val character = table.host.character
        if (table.mode == GameMode.DUPLAS) {
            // sem retrato à esquerda: os três vêm abaixo
        } else if (character != null) {
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CharacterPortrait(character, Pose.APRESENTACAO, Modifier.fillMaxWidth().testTag("table-portrait-${table.id}"), description = table.host.fullName)
                BotMotto(character.profile, fontSize = 10.sp)
            }
        } else {
            PersonaAvatar(table.host, 52.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.tables_host, table.host.fullName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GColors.Champagne,
            )
            if (character != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LevelStars(character.level)
                    // em duplas, o nível é o da mesa; a característica de cada um está nos retratos da tela da mesa
                    Text(
                        stringResource(if (table.mode == GameMode.DUPLAS) character.level.labelRes() else character.profile.tagRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = GColors.Steel,
                    )
                }
            }
            Text(rulesName(table.rules), style = MaterialTheme.typography.bodyMedium, color = GColors.Gold)
            // §14.6 aposta da mesa; sem saldo, a mesa fica bloqueada (dá para ver as regras, não para entrar)
            val affordable = Coins.canJoin(balance, table.stake)
            Text(
                stringResource(R.string.stake_line, table.stake) + if (affordable) "" else " · " + stringResource(R.string.stake_insufficient),
                Modifier.testTag("table-${table.id}-stake"),
                style = MaterialTheme.typography.bodySmall,
                color = if (affordable) GColors.Gold else GColors.Ruby,
            )
            Text(
                stringResource(
                    R.string.tables_line,
                    modeLabel(table.mode),
                    stringResource(table.difficulty.labelRes()),
                    plainPoints(table.targetScore),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = GColors.Steel,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RulesIcon(GColors.Gold, size = 26.dp)
            Text(stringResource(R.string.tables_view_rules), style = MaterialTheme.typography.labelLarge, color = GColors.Gold)
        }
    }
    if (table.mode == GameMode.DUPLAS) RosterStrip(table)
    }
}

/** §14.4 os três personagens de uma mesa de duplas, em retratos de apresentação pequenos: adversário à esquerda, parceiro e adversário à direita. */
@Composable
private fun RosterStrip(table: TableOffer) {
    Row(Modifier.fillMaxWidth().testTag("table-${table.id}-roster"), horizontalArrangement = Arrangement.SpaceEvenly) {
        seatsInDisplayOrder(table).forEach { (persona, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // mesmo retrato de apresentação das mesas individuais, só menor
                val c = persona.character
                if (c != null) {
                    CharacterPortrait(c, Pose.APRESENTACAO, Modifier.fillMaxWidth(0.9f), description = persona.fullName)
                } else {
                    PersonaAvatar(persona, 44.dp)
                }
                c?.let { BotMotto(it.profile, fontSize = 9.sp) }
                Text(persona.shortName, style = MaterialTheme.typography.labelSmall, color = GColors.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = GColors.Steel, maxLines = 1)
            }
        }
    }
}

/** Personagens da mesa de duplas na ordem em que se sentam à vista do jogador (esquerda, parceiro, direita), com o rótulo de cada um. */
private fun seatsInDisplayOrder(table: TableOffer): List<Pair<Persona, Int>> = listOf(
    table.players[2] to R.string.seat_left_short,
    table.players[1] to R.string.seat_partner,
    table.players[0] to R.string.seat_right_short,
)

// ---------- Regras da mesa ----------

/** §14.4 regras de uma mesa aberta: o jogador inicia o jogo ou volta. */
@Composable
fun TableRulesScreen(
    table: TableOffer,
    onStart: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onReroll: (() -> Unit)? = null,
    balance: Int = Int.MAX_VALUE,
    /** §16.2 mesa multijogador: o nome dela vai no título e o botão é "Publicar mesa". */
    multiplayerName: String? = null,
) {
    TableFrame(
        multiplayerName?.let { "${stringResource(R.string.table_rules_title)} · $it" } ?: stringResource(R.string.table_rules_title),
        "table-rules-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "table-rules-back", GButtonKind.Secondary, onClick = onBack)
            BottomButton(
                stringResource(if (multiplayerName != null) R.string.table_rules_publish else R.string.table_rules_start),
                "table-rules-start",
                GButtonKind.Primary,
                enabled = Coins.canJoin(balance, table.stake),
            ) { onStart(table.toConfig()) }
        },
    ) {
        // §14.5 nível dos robôs (estrelas e nome) centralizado; à direita, sortear outros (só em mesa criada pelo jogador).
        val level = BotLevel.of(table.difficulty)
        Box(Modifier.fillMaxWidth().testTag("table-rules-level")) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.table_rules_level), style = MaterialTheme.typography.titleSmall, color = GColors.Steel)
                LevelStars(level, size = 20.sp)
                Text(stringResource(level.labelRes()), style = MaterialTheme.typography.titleSmall, color = GColors.Gold)
            }
            // Mesa multijogador: os robôs não são apresentados antes do início (docs/telas.md 3.3), então não há sorteio.
            if (onReroll != null && multiplayerName == null) {
                val rerollText = stringResource(R.string.table_rules_reroll)
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp)
                        .clickable(onClickLabel = rerollText, role = Role.Button, onClick = onReroll)
                        .semantics { contentDescription = rerollText }
                        .testTag("table-rules-reroll"),
                    contentAlignment = Alignment.Center,
                ) { RecycleIcon(GColors.Gold, size = 28.dp) }
            }
        }
        if (multiplayerName == null) GPanel(Modifier.fillMaxWidth()) {
            val character = table.host.character
            if (table.mode == GameMode.DUPLAS) {
                // §14.4 a mesa de duplas já está formada: o parceiro em destaque (40% maior, sozinho na linha) e, abaixo,
                // os adversários lado a lado, na posição em que ficam no jogo.
                val (left, partner, right) = seatsInDisplayOrder(table).map { it.first }
                Column(Modifier.fillMaxWidth().testTag("table-rules-roster"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    RulesBot(partner, Modifier.fillMaxWidth(), 0.6f, stringResource(R.string.seat_partner), "table-rules-partner")
                    Text(
                        stringResource(R.string.table_rules_opponents),
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = GColors.Gold,
                    )
                    Row(Modifier.fillMaxWidth().testTag("table-rules-opponents"), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                        RulesBot(left, Modifier.weight(1f), 0.85f, null, "table-rules-left")
                        RulesBot(right, Modifier.weight(1f), 0.85f, null, "table-rules-right")
                    }
                    Text(
                        "${modeLabel(table.mode)} · ${stringResource(table.difficulty.labelRes())}",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GColors.Steel,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (character != null) {
                // §14.5 painel de apresentação: o retrato em destaque, o nome, o bordão e o texto que apresenta o perfil.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RulesBot(table.host, Modifier.fillMaxWidth(), 0.8f, null, "table-rules-portrait", title = stringResource(R.string.tables_host, table.host.fullName))
                    Text(
                        "${modeLabel(table.mode)} · ${stringResource(table.difficulty.labelRes())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GColors.Steel,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PersonaAvatar(table.host, 52.dp)
                    Text(stringResource(R.string.tables_host, table.host.fullName), style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
                }
            }
        }
        // §14.6 aposta da mesa e saldo; sem saldo para a aposta, não dá para começar
        GPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stake_line, table.stake), Modifier.testTag("table-rules-stake"), style = MaterialTheme.typography.titleMedium, color = GColors.Gold)
            if (balance != Int.MAX_VALUE) {
                Text(stringResource(R.string.coins_balance, balance), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
            }
            if (!Coins.canJoin(balance, table.stake)) {
                Text(stringResource(R.string.stake_insufficient), Modifier.testTag("table-rules-insufficient"), style = MaterialTheme.typography.bodyMedium, color = GColors.Ruby)
            }
        }
        RulesSummary(table.rules, table.targetScore)
    }
}

/**
 * §14.5 um robô na tela "Regras da mesa": retrato ocupando [portraitFraction] da largura, o rótulo da posição ([label],
 * só o parceiro), o nome (ou [title]), o bordão, o estilo de jogo e a descrição do estilo.
 */
@Composable
private fun RulesBot(persona: Persona, modifier: Modifier, portraitFraction: Float, label: String?, tag: String, title: String? = null) {
    val c = persona.character
    Column(modifier.testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = GColors.Gold, textAlign = TextAlign.Center) }
        if (c == null) {
            PersonaAvatar(persona, 52.dp)
        } else {
            CharacterPortrait(c, Pose.APRESENTACAO, Modifier.fillMaxWidth(portraitFraction), description = persona.fullName)
        }
        Text(title ?: persona.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GColors.Champagne, textAlign = TextAlign.Center)
        if (c != null) {
            BotMotto(c.profile, fontSize = 13.sp)
            Text(stringResource(c.profile.tagRes()), style = MaterialTheme.typography.labelLarge, color = GColors.Steel, textAlign = TextAlign.Center)
            Text(stringResource(c.profile.pitchRes()), style = MaterialTheme.typography.bodySmall, color = GColors.Ivory, textAlign = TextAlign.Center)
        }
    }
}

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

// ---------- Criar mesa ----------

/** §14, §14.4 configurar nova mesa: dificuldade, modo, regras e pontuação-alvo. */
@Composable
fun CreateTableScreen(
    viewModel: CreateTableViewModel,
    onCreate: (GameConfig) -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit,
    /** §16.2 criar mesa multijogador (configuração e nome da mesa); `null` = sem o tipo Multijogador. */
    onCreateMultiplayer: ((GameConfig, String) -> Unit)? = null,
    /** §16 porta das permissões do Nearby (pedidas ao escolher o tipo Multijogador). */
    accessGate: (@Composable (onReady: () -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    defaultTableName: String = "",
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var asking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (asking && accessGate != null) {
        accessGate({ asking = false; viewModel.onTypeChange(true) }, { asking = false; viewModel.onTypeChange(false) })
    }
    TableFrame(
        stringResource(R.string.create_title),
        "create-table-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "create-back", GButtonKind.Secondary, onClick = onBack)
            BottomButton(stringResource(R.string.create_submit), "create-table", GButtonKind.Primary, enabled = state.canCreate) {
                val config = viewModel.onCreate() ?: return@BottomButton
                if (state.multiplayer && onCreateMultiplayer != null) {
                    onCreateMultiplayer(config, state.tableName.trim().ifBlank { defaultTableName })
                } else {
                    onCreate(config)
                }
            }
        },
    ) {
        GPanel(Modifier.fillMaxWidth()) {
            if (onCreateMultiplayer != null) {
                Section(stringResource(R.string.create_type)) {
                    Chip(stringResource(R.string.create_type_bots), !state.multiplayer, "create-type-bots") { viewModel.onTypeChange(false) }
                    Chip(stringResource(R.string.create_type_multi), state.multiplayer, "create-type-multi") {
                        if (!state.multiplayer) asking = true
                    }
                }
            }
            if (state.multiplayer) {
                OutlinedTextField(
                    value = state.tableName,
                    onValueChange = viewModel::onTableNameChange,
                    label = { Text(stringResource(R.string.create_table_name)) },
                    placeholder = { Text(defaultTableName) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("create-table-name"),
                )
            } else {
                Section(stringResource(R.string.home_mode)) {
                    ModeCard(stringResource(R.string.mode_individual), 1, state.mode == GameMode.INDIVIDUAL, "mode-individual") {
                        viewModel.onModeChange(GameMode.INDIVIDUAL)
                    }
                    ModeCard(stringResource(R.string.mode_duplas_short), 2, state.mode == GameMode.DUPLAS, "mode-duplas") {
                        viewModel.onModeChange(GameMode.DUPLAS)
                    }
                }
            }
            Section(stringResource(R.string.home_difficulty)) {
                Difficulty.entries.forEach { difficulty ->
                    Chip(stringResource(difficulty.labelRes()), state.difficulty == difficulty, "difficulty-${difficulty.id}") {
                        viewModel.onDifficultyChange(difficulty)
                    }
                }
            }
            Section(stringResource(R.string.create_stake)) {
                Coins.STAKES.forEach { stake ->
                    Chip(stake.toString(), state.stake == stake, "stake-$stake") { viewModel.onStakeChange(stake) }
                }
            }
            Text(
                stringResource(R.string.coins_balance, state.balance) + if (state.canAfford) "" else " · " + stringResource(R.string.stake_insufficient),
                Modifier.testTag("create-balance"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.canAfford) GColors.Steel else GColors.Ruby,
            )
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
                        { Text(stringResource(R.string.home_target_too_large, CreateTableUiState.MAX_TARGET)) }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("target-field"),
            )
        }
        RulesSummary(state.rules, state.targetScore ?: state.rules.targetScore)
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
