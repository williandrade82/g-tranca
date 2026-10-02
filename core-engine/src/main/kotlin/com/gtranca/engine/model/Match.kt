package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/** Partida já encerrada e pontuada, guardada no histórico do jogo. */
@Serializable
data class RoundRecord(
    /** Número da partida no jogo, a partir de 1. */
    val number: Int,
    val result: RoundResult,
    /** Detalhamento por lado, indexado por [Side.index]. */
    val scores: List<SideScore>,
)

/**
 * Jogo (`Match`, §2, §13, §14): sequência de partidas até um lado atingir a pontuação-alvo.
 * Imutável; as transições ficam em `MatchEngine.kt` (`startMatch`, `play`, `finishRound`, `startNextRound`).
 *
 * @property targetScore pontuação-alvo (§14), inteiro positivo, fixa durante o jogo.
 * @property seed semente do jogo; a partida de índice i (0, 1, …) é distribuída com o RNG
 *   `roundRandom(seed, i)`, o que torna o jogo reprodutível e retomável a partir do JSON.
 * @property totals total acumulado por lado, indexado por [Side.index]; pode ser negativo.
 * @property history partidas encerradas e pontuadas, em ordem.
 * @property currentRound partida atual (em andamento, ou encerrada).
 * @property currentRoundRecorded a partida atual já foi pontuada e está em [history].
 * @property winner lado vencedor do jogo, quando terminado (§13).
 */
@Serializable
data class Match(
    val mode: GameMode,
    val targetScore: Int,
    val seed: Long,
    val totals: List<Int>,
    val history: List<RoundRecord>,
    val currentRound: RoundState,
    val currentRoundRecorded: Boolean = false,
    val winner: Side? = null,
) {
    init {
        require(targetScore > 0) { "Pontuação-alvo deve ser um inteiro positivo: $targetScore" }
        require(totals.size == mode.sideCount) { "Um total por lado" }
        require(currentRound.mode == mode) { "A partida atual deve ser do modo do jogo" }
        require(!currentRoundRecorded || currentRound.phase == Phase.FINISHED) { "Só partida encerrada é registrada" }
        require(winner == null || currentRoundRecorded) { "O jogo só termina ao registrar uma partida" }
    }

    /** Número da partida atual, a partir de 1. */
    val roundNumber: Int get() = if (currentRoundRecorded) history.size else history.size + 1

    /** Partida atual registrada e jogo não terminado: falta iniciar a próxima. */
    val isAwaitingNextRound: Boolean get() = currentRoundRecorded && winner == null

    /** §13 o jogo terminou com um vencedor. */
    val isOver: Boolean get() = winner != null
}
