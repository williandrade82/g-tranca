package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoTarget
import kotlin.random.Random

/** §3.6 / §14.5 taxa de acerto do robô **Fácil** no palpite da distribuição certeira. */
const val EASY_DEAL_GUESS_ACCURACY: Double = 0.05

/** §3.6 / §14.5 taxa de acerto do robô **Médio** no palpite da distribuição certeira. */
const val MEDIUM_DEAL_GUESS_ACCURACY: Double = 0.15

/** §3.6 / §14.5 taxa de acerto do robô **Difícil** no palpite da distribuição certeira. */
const val HARD_DEAL_GUESS_ACCURACY: Double = 0.25

/**
 * §3.6 escolha do palpite da distribuição certeira, compartilhada pelos três níveis de robô (§14.5).
 *
 * Com probabilidade [accuracy] escolhe, entre [legal], a largura que acerta o alvo da fase de [view]
 * ([Phase.DEAL_GUESS_HAND]: [dealGuessHandTarget]; [Phase.DEAL_GUESS_MORTO]: [dealGuessMortoTarget]); senão,
 * qualquer outra largura, uniformemente. Só usa o RNG [random] recebido (mesmo contrato dos outros bots: nunca
 * `Random` global, ver KDoc de [BotPlayer.chooseAction]), então é reprodutível com a mesma semente.
 *
 * [legal] deve conter só [Action.GuessDealRange] — exatamente o que o motor oferece nessas fases (um candidato
 * por largura possível, ver `LegalActions`). Fora dessas fases, ou se [legal] não tiver nenhum `GuessDealRange`,
 * devolve a primeira ação de [legal] sem consumir o RNG (não deveria ser chamada nesse caso).
 */
internal fun chooseDealGuess(view: PlayerView, legal: List<Action>, random: Random, accuracy: Double): Action {
    val guesses = legal.filterIsInstance<Action.GuessDealRange>()
    if (guesses.isEmpty()) return legal.first()
    val target = when (view.phase) {
        Phase.DEAL_GUESS_HAND -> dealGuessHandTarget(view.mode, view.rules)
        Phase.DEAL_GUESS_MORTO -> dealGuessMortoTarget(view.rules)
        else -> return legal.first()
    }
    val correct = guesses.filter { it.end - it.start == target }
    val wrong = guesses.filter { it.end - it.start != target }
    val wantsCorrect = random.nextDouble() < accuracy
    val pool = if (wantsCorrect && correct.isNotEmpty()) correct else wrong.ifEmpty { correct }
    return pool.random(random)
}
