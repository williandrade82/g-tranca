package com.gtranca.engine.model

import com.gtranca.engine.RuleSet
import kotlin.random.Random

/** §1 dois baralhos padrão, sem curingas de fábrica. */
object Deck {
    const val SIZE: Int = 104

    /** As 104 cartas em ordem canônica (baralho, naipe, valor). */
    fun standard(): List<Card> =
        (0..1).flatMap { deck -> Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit, deck) } } }

    /** §1 / §14.2 baralho do perfil: sem coringas (`maxWildsPerMeld` = 0), os oito 2 saem do jogo (96 cartas). */
    fun forRules(rules: RuleSet): List<Card> =
        if (rules.maxWildsPerMeld == 0) standard().filterNot { it.isWild } else standard()

    /** §1 quantidade de cartas do baralho do perfil. */
    fun size(rules: RuleSet): Int = if (rules.maxWildsPerMeld == 0) SIZE - 8 else SIZE

    /** §1 embaralha usando somente o [random] recebido (partidas reproduzíveis pela semente). */
    fun shuffled(random: Random, rules: RuleSet = RuleSet.DEFAULT): List<Card> = forRules(rules).shuffled(random)
}
