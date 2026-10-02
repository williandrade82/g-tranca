package com.gtranca.engine

/**
 * Regras fixas reunidas para facilitar ajustes futuros (§14). Não são expostas ao jogador.
 * Por ora contém apenas o necessário para a distribuição (§3); cresce com as próximas regras.
 */
data class RuleSet(
    /** §3.1 */
    val cardsPerHand: Int = 11,
    /** §3.2 */
    val cardsPerMorto: Int = 11,
    /** §3.2 */
    val mortoCount: Int = 2,
) {
    companion object {
        val DEFAULT = RuleSet()
    }
}
