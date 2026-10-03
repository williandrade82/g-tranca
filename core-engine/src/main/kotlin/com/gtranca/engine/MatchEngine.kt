package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import kotlin.random.Random

/**
 * Inicia o jogo (§2, §14): sorteia a semente do jogo com [random] e distribui a 1ª partida.
 * [targetScore] deve ser um inteiro positivo (padrão 3000); fica fixo durante o jogo.
 *
 * Reprodutibilidade: o [random] só é usado para obter a semente do jogo ([Match.seed]); cada partida
 * de índice i é distribuída com [roundRandom] (seed, i). Assim, mesma semente ⇒ mesmo jogo, e um
 * jogo salvo em JSON continua reprodutível sem guardar o estado interno do RNG.
 */
fun startMatch(
    mode: GameMode,
    targetScore: Int = RuleSet.DEFAULT.defaultTargetScore,
    random: Random,
    rules: RuleSet = RuleSet.DEFAULT,
): Match {
    require(targetScore > 0) { "Pontuação-alvo deve ser um inteiro positivo: $targetScore" }
    val seed = random.nextLong()
    return Match(
        mode = mode,
        targetScore = targetScore,
        seed = seed,
        totals = List(mode.sideCount) { 0 },
        history = emptyList(),
        currentRound = dealRound(mode, roundRandom(seed, 0), rules),
    )
}

/** Aplica uma ação na partida atual via [RoundEngine.apply] (lança se inválida). */
fun Match.play(seat: Seat, action: Action, rules: RuleSet = RuleSet.DEFAULT): Match =
    copy(currentRound = RoundEngine.apply(currentRound, seat, action, rules))

/**
 * Encerra a partida atual (já em [Phase.FINISHED]): pontua (§12), acumula os totais e registra o
 * detalhamento no histórico. §13: se algum lado tiver total ≥ alvo, vence o de maior total; empate
 * no maior total ⇒ o jogo continua com nova partida.
 */
fun Match.finishRound(rules: RuleSet = RuleSet.DEFAULT): Match {
    require(currentRound.phase == Phase.FINISHED) { "A partida atual ainda não terminou" }
    require(!currentRoundRecorded) { "A partida atual já foi registrada" }
    val scores = scoreRound(currentRound, rules)
    val newTotals = totals.mapIndexed { i, total -> total + scores[i].total }
    val record = RoundRecord(number = history.size + 1, result = currentRound.result!!, scores = scores)
    return copy(
        totals = newTotals,
        history = history + record,
        currentRoundRecorded = true,
        winner = matchWinner(newTotals, targetScore),
    )
}

/**
 * Distribui a próxima partida (§3). §4.1 ela é iniciada pelo jogador seguinte (§4.2) ao que iniciou
 * a partida anterior.
 */
fun Match.startNextRound(rules: RuleSet = RuleSet.DEFAULT): Match {
    require(isAwaitingNextRound) { "Só se inicia nova partida depois de registrar a atual e com o jogo em andamento" }
    val firstSeat = mode.nextSeat(currentRound.firstSeat)
    return copy(
        currentRound = dealRound(mode, roundRandom(seed, history.size), rules, firstSeat),
        currentRoundRecorded = false,
    )
}

/** §13 vencedor do jogo, ou `null` se ninguém atingiu o alvo ou há empate no maior total. */
private fun matchWinner(totals: List<Int>, targetScore: Int): Side? {
    if (totals.none { it >= targetScore }) return null
    val best = totals.max()
    val leaders = totals.indices.filter { totals[it] == best }
    return if (leaders.size == 1) Side(leaders.single()) else null
}

/**
 * RNG da partida de índice [roundIndex] (0 = primeira) do jogo de semente [matchSeed]:
 * `Random(matchSeed + roundIndex * 0x9E3779B97F4A7C15)` (constante de Fibonacci para espalhar as sementes).
 */
internal fun roundRandom(matchSeed: Long, roundIndex: Int): Random = Random(matchSeed + roundIndex * GOLDEN_GAMMA)

private const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL
