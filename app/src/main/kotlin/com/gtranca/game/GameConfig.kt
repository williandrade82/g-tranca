package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode

/**
 * Configuração do jogo escolhida antes de começar (§14); não muda durante o jogo.
 *
 * @property difficulty dificuldade de **todos** os jogadores virtuais (parceiro e adversários).
 * @property targetScore pontuação-alvo, inteiro positivo.
 */
data class GameConfig(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val targetScore: Int = RuleSet.DEFAULT.defaultTargetScore,
) {
    init {
        require(targetScore > 0) { "Pontuação-alvo deve ser um inteiro positivo: $targetScore" }
    }
}

/**
 * Lê a pontuação-alvo digitada (§14: inteiro positivo). Aceita só dígitos ASCII `0`–`9` (com espaços nas pontas):
 * sem sinal (`+`/`-`), separadores ou dígitos de outros sistemas de escrita. Devolve `null` se o texto não for um
 * inteiro positivo representável em `Int`.
 */
fun parseTargetScore(text: String): Int? =
    text.trim().takeIf { it.isAsciiDigits() }?.toIntOrNull()?.takeIf { it > 0 }

/** Não vazio e só com dígitos ASCII `0`–`9`. */
fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/**
 * Semente do RNG do bot do assento [seatIndex] no jogo de semente [gameSeed].
 *
 * Mesma fórmula de `Simulator.botSeed` do `:sim` (o `:app` não depende do `:sim`): assim um jogo do app
 * só com bots é idêntico ao do simulador com a mesma semente. O teste de equivalência do
 * `GameController` quebra se as duas divergirem.
 */
fun botSeed(gameSeed: Long, seatIndex: Int): Long = gameSeed * 1_000_003L + seatIndex + 1
