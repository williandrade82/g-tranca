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
    /** §12.1 cada 3 vermelho na mesa, se o lado tiver pelo menos uma canastra (limpa ou suja). */
    val redThreePoints: Int = 100,
    /** §12.1 cada 3 vermelho na mesa, se o lado não tiver canastra. */
    val redThreeWithoutCanastaPoints: Int = -100,
    /** §12.1 cada canastra limpa. */
    val cleanCanastaPoints: Int = 200,
    /** §12.1 cada canastra suja. */
    val dirtyCanastaPoints: Int = 100,
    /** §12.1 batida (só o lado vencedor). */
    val goOutPoints: Int = 100,
    /** §12.2 lado que terminou a partida sem ter pego morto. */
    val mortoNotTakenPoints: Int = -100,
    /** §12.2 3 vermelho na mão. */
    val handRedThreePoints: Int = -5,
    /** §12.2 3 preto na mão. */
    val handBlackThreePoints: Int = -5,
    /** §12.2 cartas de 4 a 10 na mão. */
    val handFourToTenPoints: Int = -8,
    /** §12.2 J, Q, K e A na mão. */
    val handFaceCardOrAcePoints: Int = -10,
    /** §12.2 coringa (2) na mão. */
    val handWildPoints: Int = -10,
    /** §14 pontuação-alvo padrão (configurável pelo jogador ao iniciar o jogo). */
    val defaultTargetScore: Int = 3000,
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
