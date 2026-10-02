package com.gtranca.engine.model

import kotlin.random.Random

/** §1 dois baralhos padrão, sem curingas de fábrica. */
object Deck {
    const val SIZE: Int = 104

    /** As 104 cartas em ordem canônica (baralho, naipe, valor). */
    fun standard(): List<Card> =
        (0..1).flatMap { deck -> Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit, deck) } } }

    /** §1 embaralha usando somente o [random] recebido (partidas reproduzíveis pela semente). */
    fun shuffled(random: Random): List<Card> = standard().shuffled(random)
}
