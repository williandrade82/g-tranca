package com.gtranca.engine

/**
 * Regras fixas reunidas para facilitar ajustes futuros (§14). Não são expostas ao jogador.
 * Cresce à medida que as regras são implementadas.
 */
data class RuleSet(
    /** §3.1 */
    val cardsPerHand: Int = 11,
    /** §3.2 */
    val cardsPerMorto: Int = 11,
    /** §3.2 */
    val mortoCount: Int = 2,
    /** §6.2 mínimo de cartas por conjunto. */
    val minMeldSize: Int = 3,
    /** §7.1 mínimo de cartas para ser canastra. */
    val minCanastaSize: Int = 6,
    /** §6.3 máximo de coringas por conjunto. */
    val maxWildsPerMeld: Int = 1,
) {
    init {
        // O posicionamento do coringa na sequência (§6.3) só é definido para no máximo 1 coringa.
        require(maxWildsPerMeld in 0..1) { "maxWildsPerMeld deve ser 0 ou 1" }
        require(minMeldSize >= 3) { "minMeldSize deve ser >= 3 (garante 2 naturais com 1 coringa)" }
    }

    companion object {
        val DEFAULT = RuleSet()
    }
}
