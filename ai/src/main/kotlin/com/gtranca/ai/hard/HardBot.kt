package com.gtranca.ai.hard

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.HardBotConfig
import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.PublicHistory
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import kotlin.random.Random

/**
 * Bot de dificuldade **Difícil** (§14): ISMCTS com determinização das cartas ocultas e o Médio como
 * política de simulação (ver [Ismcts] para o algoritmo).
 *
 * Como os outros bots, recebe só a [PlayerView] e as ações legais, e lembra só o histórico público
 * ([observe]): as cartas que cada assento levou do lixo (§5.2, §5.6) entram nas determinizações como cartas
 * sabidas na mão dele ([normalizeKnown]). As simulações rodam sobre mundos sorteados, nunca sobre o estado
 * real da partida.
 *
 * **Custo e thread:** [chooseAction] é síncrona e faz uma busca de CPU (até [HardBotConfig.iterations]
 * iterações e, se houver, [HardBotConfig.timeLimitMillis] ms). O `:app` deve chamá-la fora da thread
 * principal, por exemplo `withContext(Dispatchers.Default) { bot.chooseAction(view, legal) }`. O bot não
 * cria threads e não é seguro para uso concorrente: uma instância por assento, uma decisão por vez.
 *
 * Determinismo: a mesma semente e as mesmas entradas (inclusive os eventos observados) dão as mesmas escolhas
 * enquanto a busca completar as [HardBotConfig.iterations] iterações. Se o teto de tempo
 * ([HardBotConfig.timeLimitMillis], rede de segurança) for atingido, o número de iterações passa a depender
 * da máquina e a jogada deixa de ser reproduzível; [timeLimitHits] conta essas decisões.
 *
 * Robustez: se a busca falhar por qualquer exceção, a decisão é a do Médio ([searchFailures] conta isso).
 * Uma decisão com uma única ação legal (ou uma única candidata) volta direto, sem busca.
 *
 * @param clock relógio em nanossegundos (injetável nos testes do teto de tempo).
 */
class HardBot(
    private val random: Random,
    private val config: HardBotConfig = HardBotConfig.DEFAULT,
    private val clock: () -> Long = System::nanoTime,
) : BotPlayer {

    private val history = PublicHistory()
    private val events = ArrayList<PublicEvent>()

    /** Médio com o histórico real: só ordena e poda as candidatas (não usa o RNG nessa função). */
    private val scorer = MediumBot(Random(0))

    /** Iterações da última busca (0 se a decisão foi direta). Para medições e testes. */
    var lastIterations: Int = 0
        private set

    /** Na última busca, a escolha foi diferente da preferida do Médio. Para medições e testes. */
    var lastOverrodeMedium: Boolean = false
        private set

    /** Decisões em que a busca parou pelo teto de tempo (jogada não reproduzível). Para o app e o `:sim` medirem. */
    var timeLimitHits: Int = 0
        private set

    /** Decisões em que a busca falhou com exceção e a jogada foi a do Médio. */
    var searchFailures: Int = 0
        private set

    /** Cópia dos eventos públicos observados nesta partida (para testes: a busca não os altera). */
    internal fun observedEvents(): List<PublicEvent> = events.toList()

    /** Cópia das cartas sabidas na mão de cada assento, segundo o histórico público (para testes). */
    internal fun knownHands(view: PlayerView): Map<Int, List<Card>> =
        view.mode.seats.associate { it.index to history.knownHand(it).toList() }

    override fun observe(event: PublicEvent) {
        history.record(event)
        scorer.observe(event)
        events += event
    }

    override fun onNewRound() {
        history.clear()
        scorer.onNewRound()
        events.clear()
    }

    override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "Sem ações legais" }
        lastIterations = 0
        lastOverrodeMedium = false
        if (legal.size == 1) return legal.single()
        val result = try {
            val known = normalizeKnown(view, history)
            Ismcts(config, random, clock).search(view, legal, scorer, known, events.toList())
        } catch (_: Exception) {
            // Uma falha da busca nunca derruba a partida: joga como o Médio.
            searchFailures++
            return scorer.chooseAction(view, legal)
        }
        lastIterations = result.iterations
        lastOverrodeMedium = result.action != result.baseline
        if (result.hitTimeLimit) timeLimitHits++
        // A busca só devolve candidatas tiradas de `legal`; a conferência é só uma garantia extra.
        return result.action.takeIf { it in legal } ?: scorer.chooseAction(view, legal)
    }
}
