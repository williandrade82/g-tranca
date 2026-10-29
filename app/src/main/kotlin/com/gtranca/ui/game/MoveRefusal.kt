package com.gtranca.ui.game

import androidx.annotation.StringRes
import com.gtranca.R
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.RuleError

/** Nome da regra da mesa para o texto; o nome de uma regra personalizada é trocado por "Personalizada" na UI. */
sealed interface RuleLabel {
    data class Named(val name: String) : RuleLabel
    data object Custom : RuleLabel

    companion object {
        /** Mesmo critério de `rulesName`: perfis da casa pelo nome, qualquer outro como personalizada. */
        fun of(rules: RuleSet): RuleLabel =
            if (rules.id == RuleSet.CLASSICA.id || rules.id == RuleSet.PAULISTA.id) Named(rules.name) else Custom
    }
}

/** Um texto de `strings.xml` com seus argumentos; um argumento [RuleLabel] é trocado pelo nome da regra ao desenhar. */
data class RefusalText(@StringRes val res: Int, val args: List<Any> = emptyList())

/**
 * Conteúdo do *modal de jogada não permitida* (docs/telas.md 4): título curto, o que aconteceu, a regra da mesa
 * (parágrafos; vazio = sem seção) e o que fazer (parágrafos; vazio = sem seção).
 */
data class RefusalContent(
    val title: RefusalText,
    val happened: RefusalText,
    val rule: List<RefusalText> = emptyList(),
    val tip: List<RefusalText> = emptyList(),
)

/**
 * Explicação pura de uma jogada recusada. O motivo vem de `RoundEngine.validate` (já traduzido em [UiMessage.Rejected]);
 * aqui só se acrescenta a regra da mesa (`view.rules`) e a situação pública do próprio jogador (a mão dele, os jogos
 * e os mortos da mesa), nunca cartas ocultas (definition.md §3 a §11, §14.2).
 */
object MoveRefusal {

    /** `null` quando a mensagem não é recusa de jogada (ex.: pedir para mostrar as cartas no multijogador). */
    fun explain(message: UiMessage, view: PlayerView): RefusalContent? = when (message) {
        UiMessage.ShowCardsToPlay -> null
        UiMessage.SelectOneCardToDiscard -> RefusalContent(
            title = t(R.string.refusal_title_select_one),
            happened = t(R.string.msg_select_one_to_discard),
            rule = listOf(t(R.string.refusal_rule_one_discard)), // §8 exatamente um descarte
            tip = listOf(t(R.string.refusal_tip_select_one)),
        )
        is UiMessage.Rejected -> explain(message.error, view)
    }

    fun explain(error: RuleError?, view: PlayerView): RefusalContent {
        val rules = view.rules
        val name = RuleLabel.of(rules)
        val happened = t(error.messageRes())
        return when (error) {
            null -> RefusalContent(t(R.string.refusal_title_generic), happened)

            ActionError.ROUND_FINISHED -> RefusalContent(t(R.string.refusal_title_round_finished), happened)
            ActionError.NOT_YOUR_TURN -> RefusalContent(
                t(R.string.refusal_title_not_your_turn), happened,
                tip = listOf(t(R.string.refusal_tip_wait_turn)),
            )
            // §4.3 a primeira ação da vez é comprar ou pegar o lixo
            ActionError.MUST_DRAW_FIRST -> RefusalContent(
                t(R.string.refusal_title_must_draw), happened,
                rule = listOf(t(R.string.refusal_rule_draw_once)),
                tip = listOf(t(R.string.refusal_tip_draw)),
            )
            ActionError.ALREADY_DREW -> RefusalContent(
                t(R.string.refusal_title_already_drew), happened,
                rule = listOf(t(R.string.refusal_rule_draw_once)),
                tip = listOf(t(R.string.refusal_tip_after_draw)),
            )
            // §10 monte esgotado; §14.2 mortoBecomesStock
            ActionError.STOCK_EXHAUSTED -> RefusalContent(
                t(R.string.refusal_title_stock_exhausted), happened,
                rule = listOf(
                    if (rules.mortoBecomesStock) t(R.string.refusal_rule_stock_morto_gone, name)
                    else t(R.string.refusal_rule_stock_morto_never, name),
                ),
                tip = listOf(t(R.string.refusal_tip_stock_exhausted)),
            )
            // §10.2 só se recusa a compra com monte e mortos esgotados
            ActionError.DECLINE_NOT_ALLOWED -> RefusalContent(
                t(R.string.refusal_title_decline_not_allowed), happened,
                rule = listOf(t(R.string.refusal_rule_decline)),
                tip = listOf(t(R.string.refusal_tip_draw)),
            )
            // §5.5
            ActionError.DISCARD_PILE_EMPTY -> RefusalContent(
                t(R.string.refusal_title_pile_empty), happened,
                rule = listOf(t(R.string.refusal_rule_pile_empty)),
                tip = listOf(t(R.string.refusal_tip_buy_stock)),
            )
            // §5.3
            ActionError.DISCARD_PILE_LOCKED -> RefusalContent(
                t(R.string.refusal_title_pile_locked), happened,
                rule = listOf(t(R.string.refusal_rule_pile_locked)),
                tip = listOf(t(R.string.refusal_tip_buy_stock)),
            )
            // §5.1
            ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS -> RefusalContent(
                t(R.string.refusal_title_pile_needs_cards), happened,
                rule = listOf(t(R.string.refusal_rule_pile_top)) + groupRule(rules, name),
                tip = listOf(t(R.string.refusal_tip_pile_top)),
            )
            ActionError.CARD_NOT_IN_HAND -> RefusalContent(
                t(R.string.refusal_title_card_not_in_hand), happened,
                tip = listOf(t(R.string.refusal_tip_select_hand)),
            )
            // §6.5 / §8
            ActionError.CANNOT_DISCARD_RED_THREE -> RefusalContent(
                t(R.string.refusal_title_red_three), happened,
                rule = listOf(t(R.string.refusal_rule_red_three)),
                tip = listOf(t(R.string.refusal_tip_discard_other)),
            )
            // §8 / §9.5 / §11.1
            ActionError.NO_MORTO_AVAILABLE -> goOutRefusal(view, name, emptyHand = true, noMorto = true)
            ActionError.NO_CANASTA_TO_GO_OUT -> goOutRefusal(view, name, emptyHand = true, noMorto = false)
            ActionError.MUST_KEEP_CARD_TO_DISCARD -> goOutRefusal(view, name, emptyHand = false, noMorto = false)

            // §6.2
            MeldError.TOO_FEW_CARDS -> RefusalContent(
                t(R.string.refusal_title_too_few), happened,
                rule = listOf(t(R.string.refusal_rule_min_meld, name, rules.minMeldSize)),
                tip = listOf(t(R.string.refusal_tip_more_cards)),
            )
            MeldError.NO_CARDS -> RefusalContent(
                t(R.string.refusal_title_no_cards), happened,
                tip = listOf(t(R.string.refusal_tip_select_to_add)),
            )
            MeldError.DUPLICATE_CARD -> RefusalContent(
                t(R.string.refusal_title_duplicate), happened,
                tip = listOf(t(R.string.refusal_tip_each_once)),
            )
            // §6.2 / §6.5 / §6.6
            MeldError.CONTAINS_THREE -> RefusalContent(
                t(R.string.refusal_title_three), happened,
                rule = listOf(t(R.string.refusal_rule_three)),
                tip = listOf(t(R.string.refusal_tip_three)),
            )
            // §6.3 / §14.2 maxWildsPerMeld
            MeldError.TOO_MANY_WILDS -> RefusalContent(
                t(R.string.refusal_title_too_many_wilds), happened,
                rule = listOf(
                    if (rules.maxWildsPerMeld == 0) t(R.string.refusal_rule_no_wilds, name)
                    else t(R.string.refusal_rule_one_wild, name),
                ),
                tip = listOf(t(R.string.refusal_tip_one_wild)),
            )
            MeldError.WILDS_NOT_ALLOWED -> RefusalContent(
                t(R.string.refusal_title_no_wilds), happened,
                rule = listOf(t(R.string.refusal_rule_no_wilds, name)),
                tip = listOf(t(R.string.refusal_tip_remove_wild)),
            )
            // §6.1
            MeldError.NOT_A_SEQUENCE_OR_GROUP -> RefusalContent(
                t(R.string.refusal_title_not_meld), happened,
                rule = listOf(t(R.string.refusal_rule_meld_kinds)),
                tip = listOf(t(R.string.refusal_tip_check_cards)),
            )
            MeldError.REPEATED_RANK -> RefusalContent(
                t(R.string.refusal_title_repeated), happened,
                rule = listOf(t(R.string.refusal_rule_repeated)),
                tip = listOf(t(R.string.refusal_tip_remove_repeated)),
            )
            // §6.2 o Ás só vale como carta alta
            MeldError.NOT_CONSECUTIVE -> RefusalContent(
                t(R.string.refusal_title_not_consecutive), happened,
                rule = listOf(t(R.string.refusal_rule_consecutive)),
                tip = listOf(t(R.string.refusal_tip_consecutive)),
            )
            MeldError.WRONG_SUIT -> RefusalContent(
                t(R.string.refusal_title_wrong_suit), happened,
                rule = listOf(t(R.string.refusal_rule_wrong_suit)),
                tip = listOf(t(R.string.refusal_tip_same_suit)),
            )
            MeldError.WRONG_RANK -> RefusalContent(
                t(R.string.refusal_title_wrong_rank), happened,
                rule = listOf(t(R.string.refusal_rule_wrong_rank)),
                tip = listOf(t(R.string.refusal_tip_same_rank)),
            )
            // §6.1 / §14.2 groupRanks
            MeldError.GROUP_RANK_NOT_ALLOWED -> RefusalContent(
                t(R.string.refusal_title_group_rank), happened,
                rule = groupRule(rules, name).ifEmpty { listOf(t(R.string.refusal_rule_group_rank_generic, name)) },
                tip = listOf(t(R.string.refusal_tip_sequence_instead)),
            )
            // §6.4
            MeldError.CONTIGUOUS_SEQUENCE -> RefusalContent(
                t(R.string.refusal_title_contiguous), happened,
                rule = listOf(t(R.string.refusal_rule_contiguous)),
                tip = listOf(t(R.string.refusal_tip_add_existing)),
            )
            MeldError.MELD_NOT_FOUND -> RefusalContent(
                t(R.string.refusal_title_meld_not_found), happened,
                tip = listOf(t(R.string.refusal_tip_pick_meld)),
            )
            else -> RefusalContent(t(R.string.refusal_title_generic), happened)
        }
    }

    /** §6.1 / §14.2 `groupRanks`: quando a mesa restringe os grupos, a restrição vale para todo grupo. */
    private fun groupRule(rules: RuleSet, name: RuleLabel): List<RefusalText> = when (rules.groupRanks) {
        GroupRanks.ANY -> emptyList()
        GroupRanks.FACE_AND_ACE -> listOf(t(R.string.refusal_rule_group_face, name))
        GroupRanks.NONE -> listOf(t(R.string.refusal_rule_group_none, name))
    }

    /**
     * Ficar sem cartas (ou com 1 carta que seria a do descarte) sem poder bater: a falta é sempre da canastra exigida
     * para bater (§11.1, `goOutRequiresCleanCanasta`, §14.2), pois o morto só impede quando está disponível, e aí
     * ficar sem cartas é permitido (§9). [emptyHand] = a jogada zera a mão; senão deixaria 1 carta (§8).
     */
    private fun goOutRefusal(view: PlayerView, name: RuleLabel, emptyHand: Boolean, noMorto: Boolean): RefusalContent {
        val rules = view.rules
        // §7.2 sem coringas no jogo não existe canastra suja e a exigência de limpa não se aplica.
        val needsClean = rules.goOutRequiresCleanCanasta && rules.maxWildsPerMeld > 0
        val melds = view.tables[view.side.index].melds.map { it.meld }
        val onlyDirty = needsClean && melds.any { it.isDirtyCanasta(rules) }
        val largest = melds.maxOfOrNull { it.cards.size } ?: 0

        val title = if (emptyHand) R.string.refusal_title_go_out else R.string.refusal_title_keep_card
        val happened = when {
            emptyHand && onlyDirty -> R.string.refusal_happened_empty_dirty
            emptyHand -> R.string.refusal_happened_empty_none
            onlyDirty -> R.string.refusal_happened_keep_dirty
            else -> R.string.refusal_happened_keep_none
        }
        val rule = buildList {
            if (!emptyHand) add(t(R.string.refusal_rule_keep_two))
            add(
                if (needsClean) t(R.string.refusal_rule_go_out_clean, name, rules.minCanastaSize)
                else t(R.string.refusal_rule_go_out_any, name, rules.minCanastaSize),
            )
            add(t(if (noMorto) R.string.refusal_rule_no_morto else R.string.refusal_rule_morto_taken))
        }
        val tip = buildList {
            if (onlyDirty) {
                add(t(R.string.refusal_tip_go_out_dirty))
            } else {
                add(t(R.string.refusal_tip_go_out_none, rules.minCanastaSize))
                if (largest in 1 until rules.minCanastaSize) {
                    add(t(R.string.refusal_tip_largest, largest, rules.minCanastaSize - largest))
                }
            }
        }
        return RefusalContent(t(title), t(happened), rule, tip)
    }

    private fun t(@StringRes res: Int, vararg args: Any) = RefusalText(res, args.toList())
}
