package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat

/**
 * Jogador virtual. Enxerga só o [PlayerView] do seu assento (nunca o estado completo da partida)
 * e escolhe uma das ações legais calculadas pelo motor por quem roda a partida.
 *
 * Contrato:
 * - [chooseAction] devolve sempre um elemento de `legal` (o bot nunca valida regras por conta própria);
 * - aleatoriedade só pelo RNG com semente injetada na construção: mesma semente e mesmas entradas
 *   produzem as mesmas escolhas. Para bots com busca limitada por orçamento (o Difícil), a reprodutibilidade
 *   vale com o orçamento por iterações; se um teto de tempo for atingido, o número de iterações passa a
 *   depender da máquina e a jogada deixa de ser reproduzível (o bot registra quando isso acontece).
 */
interface BotPlayer {

    /** Escolhe uma ação de [legal] (não vazia) para o jogador da vez descrito em [view]. */
    fun chooseAction(view: PlayerView, legal: List<Action>): Action

    /**
     * Histórico público: quem roda a partida avisa todos os bots de cada ação aplicada, inclusive
     * as do próprio bot. Uma compra do monte não revela a carta comprada ([Action.DrawFromStock]
     * não tem carta). Implementação padrão: ignora.
     */
    fun observe(event: PublicEvent) {}

    /** Avisado quando uma nova partida (`Round`) é distribuída. Implementação padrão: ignora. */
    fun onNewRound() {}
}

/**
 * Ação pública aplicada pelo jogador [seat].
 *
 * @property takenFromDiscard em [Action.TakeDiscardPile], as cartas do lixo que foram para a mão
 *   desse jogador (o lixo sem o topo, §5.2); é informação pública, pois o lixo é aberto (§5.6).
 *   Vazio para as demais ações.
 */
data class PublicEvent(
    val seat: Seat,
    val action: Action,
    val takenFromDiscard: List<Card> = emptyList(),
)
