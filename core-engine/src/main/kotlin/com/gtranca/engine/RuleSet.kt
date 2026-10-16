package com.gtranca.engine

import com.gtranca.engine.model.Rank
import kotlinx.serialization.Serializable

/** §6.1 / §14.2 `groupRanks`: números que podem formar grupo. */
@Serializable
enum class GroupRanks {
    /** Qualquer número de 4 a A (base). */
    ANY,
    /** Só A, K, Q, J. */
    FACE_AND_ACE,
    /** Grupos proibidos (só sequências). */
    NONE;

    fun allows(rank: Rank): Boolean = when (this) {
        ANY -> true
        FACE_AND_ACE -> rank in Rank.JACK..Rank.ACE
        NONE -> false
    }
}

/**
 * Perfil de regras (§14.3): identidade + valor de cada parâmetro de regra (§14.2). Os demais campos são
 * regras fixas (iguais em todo perfil, não expostas ao jogador), reunidas aqui para facilitar ajustes.
 * Um perfil novo é a cópia de outro com campos alterados ([duplicate]); o jogo guarda a cópia usada (§14.4).
 */
@Serializable
data class RuleSet(
    /** §14.3 identificador estável do perfil. */
    val id: String = "classica",
    /** §14.3 nome mostrado ao jogador. */
    val name: String = "Clássica",
    /** §14.3 versão do perfil. */
    val version: Int = 1,
    /** §14.3 perfil de origem (`null` na base). */
    val baseId: String? = null,
    /** §6.1 / §14.2 `groupRanks`. */
    val groupRanks: GroupRanks = GroupRanks.ANY,
    /** §10 / §14.2 `mortoBecomesStock`: com o monte vazio, um morto disponível vira monte. */
    val mortoBecomesStock: Boolean = true,
    /** §11.1 / §14.2 `goOutRequiresCleanCanasta`: bater exige canastra limpa (suja não vale). */
    val goOutRequiresCleanCanasta: Boolean = false,
    /** §3.1 */
    val cardsPerHand: Int = 11,
    /** §3.2 */
    val cardsPerMorto: Int = 11,
    /** §3.2 */
    val mortoCount: Int = 2,
    /** §6.2 mínimo de cartas por conjunto. */
    val minMeldSize: Int = 3,
    /** §7.1 mínimo de cartas para ser canastra. */
    val minCanastaSize: Int = 7,
    /** §6.3 / §14.2 máximo de coringas por conjunto (0 ou 1). */
    val maxWildsPerMeld: Int = 1,
    /** §12.1 cada 3 vermelho na mesa, se o lado tiver pelo menos uma canastra (limpa ou suja). */
    val redThreePoints: Int = 100,
    /** §12.1 cada 3 vermelho na mesa, se o lado não tiver canastra. */
    val redThreeWithoutCanastaPoints: Int = -100,
    /** §7.2 / §12.1 / §14.2 cada canastra limpa. */
    val cleanCanastaPoints: Int = 200,
    /** §7.2 / §12.1 / §14.2 cada canastra suja. */
    val dirtyCanastaPoints: Int = 100,
    /** §12.1 batida (só o lado vencedor). */
    val goOutPoints: Int = 100,
    /** §12.1 cada 3 vermelho baixado na mesa, como carta (além de [redThreePoints]/[redThreeWithoutCanastaPoints]). */
    val tableRedThreePoints: Int = 5,
    /** §12.1 cada carta de 4 a 10 em conjunto na mesa. */
    val tableFourToTenPoints: Int = 8,
    /** §12.1 cada J, Q, K e A em conjunto na mesa. */
    val tableFaceCardOrAcePoints: Int = 10,
    /** §12.1 cada coringa (2) em conjunto na mesa. */
    val tableWildPoints: Int = 10,
    /** §12.2 lado que terminou a partida sem ter pego morto. */
    val mortoNotTakenPoints: Int = -100,
    /** §12.2 3 vermelho na mão. */
    val handRedThreePoints: Int = -5,
    /** §12.2 / §14.2 3 preto na mão. */
    val handBlackThreePoints: Int = -5,
    /** §12.2 cartas de 4 a 10 na mão. */
    val handFourToTenPoints: Int = -8,
    /** §12.2 J, Q, K e A na mão. */
    val handFaceCardOrAcePoints: Int = -10,
    /** §12.2 coringa (2) na mão. */
    val handWildPoints: Int = -10,
    /** §13 / §14.2 pontuação-alvo do perfil (a mesa pode ajustá-la ao iniciar o jogo, §14). */
    val targetScore: Int = 3000,
) {
    init {
        // O posicionamento do coringa na sequência (§6.3) só é definido para no máximo 1 coringa.
        require(maxWildsPerMeld in 0..1) { "maxWildsPerMeld deve ser 0 ou 1" }
        require(minMeldSize >= 3) { "minMeldSize deve ser >= 3 (garante 2 naturais com 1 coringa)" }
        // §14.2 faixas dos parâmetros de regra.
        require(cleanCanastaPoints >= 0) { "cleanCanastaPoints deve ser >= 0" }
        require(dirtyCanastaPoints >= 0) { "dirtyCanastaPoints deve ser >= 0" }
        require(handBlackThreePoints <= 0) { "handBlackThreePoints deve ser <= 0" }
        require(targetScore > 0) { "targetScore deve ser > 0" }
    }

    /** §14.3 cópia deste perfil como novo perfil personalizado (origem = este), para depois alterar os parâmetros. */
    fun duplicate(id: String, name: String): RuleSet = copy(id = id, name = name, version = 1, baseId = this.id)

    companion object {
        /** §14.3 perfil da casa Clássica: as regras-base. */
        val CLASSICA = RuleSet()

        /** §14.3 perfil da casa Tranca Paulista. */
        val PAULISTA = CLASSICA.copy(
            id = "paulista",
            name = "Tranca Paulista",
            baseId = CLASSICA.id,
            groupRanks = GroupRanks.FACE_AND_ACE,
            mortoBecomesStock = false,
            goOutRequiresCleanCanasta = true,
            handBlackThreePoints = -100,
        )

        /** §14.3 perfis da casa, na ordem de exibição. */
        val HOUSE = listOf(CLASSICA, PAULISTA)

        val DEFAULT = CLASSICA
    }
}
