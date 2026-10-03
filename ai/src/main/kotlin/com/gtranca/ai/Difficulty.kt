package com.gtranca.ai

import com.gtranca.ai.hard.HardBot
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
 *
 * [hardConfig] só vale para [Difficulty.DIFICIL]; o padrão ([HardBotConfig.DEFAULT]) tem teto de 800 ms por
 * decisão, para o app. O Difícil faz uma busca de CPU em `chooseAction`: chame-o fora da thread principal
 * (ex.: `Dispatchers.Default`).
 */
fun createBot(difficulty: Difficulty, random: Random, hardConfig: HardBotConfig = HardBotConfig.DEFAULT): BotPlayer =
    when (difficulty) {
        Difficulty.FACIL -> EasyBot(random)
        Difficulty.MEDIO -> MediumBot(random)
        Difficulty.DIFICIL -> HardBot(random, hardConfig)
    }
