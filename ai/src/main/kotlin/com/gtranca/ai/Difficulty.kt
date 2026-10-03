package com.gtranca.ai

import kotlin.random.Random

/** Dificuldade dos jogadores virtuais (§14). */
enum class Difficulty(val id: String) {
    FACIL("facil"),
    MEDIO("medio"),
    DIFICIL("dificil");

    companion object {
        fun fromId(id: String): Difficulty =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Dificuldade inválida: '$id'")
    }
}

/**
 * Cria o bot da [difficulty] com o RNG [random] (semente injetada).
 * Implementados: [Difficulty.FACIL] e [Difficulty.MEDIO]; [Difficulty.DIFICIL] ainda não.
 */
fun createBot(difficulty: Difficulty, random: Random): BotPlayer = when (difficulty) {
    Difficulty.FACIL -> EasyBot(random)
    Difficulty.MEDIO -> MediumBot(random)
    Difficulty.DIFICIL ->
        throw UnsupportedOperationException("Bot de dificuldade '${difficulty.id}' ainda não implementado")
}
