package com.gtranca.ai.hard

import com.gtranca.ai.HardBotConfig
import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.determinize
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Busca ISMCTS (Information Set Monte Carlo Tree Search) de uma decisão do bot Difícil.
 *
 * **Variante: single-observer (SO-ISMCTS), com a árvore restrita à vez do observador.** A árvore guarda só
 * as ações do próprio assento na jogada corrente (pegar o lixo/comprar, baixar quantas vezes quiser,
 * descartar, §4.3), até [HardBotConfig.treeDepth] ações; ela é indexada pelo histórico de ações dele, ou
 * seja, pelo seu conjunto de informação. As jogadas dos outros assentos (adversários e parceiro) não entram
 * na árvore: são jogadas pela política de simulação (o Médio), cada um com a SUA vista do mundo sorteado.
 * Assim nenhum outro jogador "enxerga" as cartas ocultas da determinização (o defeito clássico de pôr os
 * adversários na árvore do SO-ISMCTS), e o fator de ramificação fica pequeno. A decisão é refeita a cada
 * ação real, então a árvore não precisa ir além da jogada.
 *
 * Cada iteração:
 * 1. **Determinização**: um mundo novo sorteado a partir da vista ([determinize]), com as cartas sabidas.
 * 2. **Seleção** (UCT com contagem de disponibilidade): em cada nó, as candidatas são as melhores ações
 *    legais NAQUELE mundo segundo a heurística do Médio ([MediumBot.rankedCandidates], no máximo
 *    [HardBotConfig.candidatesPerKind] por tipo). Depois de comprar do monte a mão muda de mundo para
 *    mundo; por isso cada filho conta quantas vezes esteve disponível e o UCB usa essa contagem. Na raiz
 *    disputam só as **desafiantes** (todas as candidatas menos a preferida do Médio, a referência).
 * 3. **Expansão**: a primeira candidata ainda sem nó, na ordem de preferência do Médio.
 * 4. **Simulação**: o Médio joga por todos os assentos até a partida terminar ou até
 *    [HardBotConfig.rolloutTurns] passagens de vez; aí [Evaluation] dá a recompensa (pontuação §12,
 *    normalizada, do ponto de vista do lado do bot).
 * 5. **Referência pareada** (redução de variância por números aleatórios comuns): no MESMO mundo e com as
 *    MESMAS sementes das políticas, simula também a preferida do Médio. O valor retropropagado é a
 *    **vantagem** (recompensa do caminho − recompensa da referência). Boa parte da variância vem das cartas
 *    sorteadas (o monte do mundo é o mesmo nas duas simulações), e ela se cancela na diferença: com poucas
 *    iterações, comparar as médias cruas das candidatas seria quase só ruído.
 * 6. **Retropropagação** da vantagem pelo caminho.
 *
 * Escolha final, conservadora: a desafiante de maior vantagem média troca a preferida do Médio só se tiver
 * ao menos [HardBotConfig.minVisitsToOverride] visitas, vantagem média acima de
 * [HardBotConfig.overrideMargin] e acima de [HardBotConfig.overrideConfidence] erros-padrão. Senão, fica a
 * do Médio. Assim o Difícil joga como o Médio onde a busca não tem evidência e só diverge com ela.
 *
 * Determinismo: só usa o [random] recebido; com orçamento só por iterações, mesma entrada ⇒ mesma escolha.
 */
internal class Ismcts(
    private val config: HardBotConfig,
    private val random: Random,
    private val clock: () -> Long,
) {
    /** Nó da árvore: estatísticas (vantagem) da ação que leva a ele e os filhos por ação canônica. */
    private class Node {
        val children = LinkedHashMap<Action, Node>()
        var visits = 0
        var total = 0.0
        var totalSquares = 0.0
        var availability = 0
        val mean: Double get() = if (visits == 0) 0.0 else total / visits

        /** Erro-padrão da média (infinito com menos de 2 visitas). */
        val standardError: Double
            get() {
                if (visits < 2) return Double.POSITIVE_INFINITY
                val variance = ((totalSquares - total * total / visits) / (visits - 1)).coerceAtLeast(0.0)
                return sqrt(variance / visits)
            }
    }

    /**
     * Resultado da busca: a ação escolhida (elemento de `legal`), quantas iterações rodaram e a preferida do
     * Médio ([baseline]), para medir quando a busca discorda dele. [hitTimeLimit]: a busca parou pelo teto
     * de tempo antes de completar as iterações (a jogada deixa de ser reproduzível).
     */
    class Result(val action: Action, val iterations: Int, val baseline: Action, val hitTimeLimit: Boolean = false)

    /**
     * Escolhe uma ação de [legal] para a vista [view].
     *
     * @param scorer Médio com o histórico público real, usado só para ordenar e podar candidatas.
     * @param known cartas sabidas nas mãos alheias, já normalizadas ([normalizeKnown]).
     * @param events eventos públicos desta partida até agora, para montar o histórico das políticas.
     */
    fun search(
        view: PlayerView,
        legal: List<Action>,
        scorer: MediumBot,
        known: Map<Seat, List<Card>>,
        events: List<PublicEvent>,
    ): Result {
        val rootCandidates = scorer.rankedCandidates(view, legal, config.candidatesPerKind)
        val baseline = rootCandidates.first()
        if (rootCandidates.size == 1) return Result(baseline, 0, baseline)

        val root = Node()
        val deadline = config.timeLimitMillis?.let { clock() + it * NANOS_PER_MILLI }
        var iterations = 0
        while (iterations < config.iterations && (deadline == null || clock() < deadline)) {
            val world = sampleWorld(view, known) ?: break
            iterate(root, world, view.seat, rootCandidates, scorer, events)
            iterations++
        }
        val hitTimeLimit = iterations < config.iterations && deadline != null && clock() >= deadline
        return Result(finalChoice(root, rootCandidates), iterations, baseline, hitTimeLimit)
    }

    /** Escolha final conservadora (ver a documentação da classe). */
    private fun finalChoice(root: Node, rootCandidates: List<Action>): Action {
        var best = rootCandidates.first()
        var bestMean = Double.NEGATIVE_INFINITY
        for (action in rootCandidates.drop(1)) {
            val node = root.children[action.key()] ?: continue
            if (passesOverride(node.mean, node.standardError, node.visits, config) && node.mean > bestMean) {
                best = action
                bestMean = node.mean
            }
        }
        return best
    }

    /** Uma determinização; nunca lança (se `known` não servir, sorteia sem ele; se nem isso, `null`). */
    private fun sampleWorld(view: PlayerView, known: Map<Seat, List<Card>>): RoundState? =
        try {
            view.determinize(random, known)
        } catch (_: IllegalArgumentException) {
            try {
                view.determinize(random)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun iterate(
        root: Node,
        world: RoundState,
        me: Seat,
        rootCandidates: List<Action>,
        scorer: MediumBot,
        events: List<PublicEvent>,
    ) {
        val side = world.mode.sideOf(me)
        val policySeed = random.nextLong()
        val policies = Rollout.policies(world, Random(policySeed), events)
        val challengers = rootCandidates.drop(1)
        var state = world
        var node = root
        val path = ArrayList<Node>()

        // seleção e expansão, só na vez do próprio assento
        while (state.phase != Phase.FINISHED && state.currentSeat == me && path.size < config.treeDepth) {
            val candidates = if (node === root) challengers else candidatesIn(state, me, scorer)
            if (candidates.isEmpty()) break
            for (action in candidates) node.children[action.key()]?.let { it.availability++ }

            val unexpanded = candidates.firstOrNull { it.key() !in node.children }
            val (action, child) = if (unexpanded != null) {
                val created = Node().also { it.availability = 1 }
                node.children[unexpanded.key()] = created
                unexpanded to created
            } else {
                selectUcb(node, candidates)
            }
            state = Rollout.step(state, me, action, policies)
            path += child
            node = child
            if (unexpanded != null) break
        }
        val reward = Rollout.play(state, policies, config.rolloutTurns, config.rewardScale, side)

        // referência pareada: a preferida do Médio no mesmo mundo, com as mesmas sementes
        val basePolicies = Rollout.policies(world, Random(policySeed), events)
        val afterBaseline = Rollout.step(world, me, rootCandidates.first(), basePolicies)
        val baselineReward = Rollout.play(afterBaseline, basePolicies, config.rolloutTurns, config.rewardScale, side)

        val advantage = reward - baselineReward
        for (n in path) {
            n.visits++
            n.total += advantage
            n.totalSquares += advantage * advantage
        }
    }

    private fun candidatesIn(state: RoundState, me: Seat, scorer: MediumBot): List<Action> {
        val legal = RoundEngine.legalActions(state, me)
        if (legal.isEmpty()) return legal
        return scorer.rankedCandidates(state.sampledView(me), legal, config.candidatesPerKind)
    }

    private fun selectUcb(node: Node, candidates: List<Action>): Pair<Action, Node> {
        var best: Pair<Action, Node>? = null
        var bestValue = Double.NEGATIVE_INFINITY
        for (action in candidates) {
            val child = node.children.getValue(action.key())
            val value = child.mean + config.explorationConstant * sqrt(ln(child.availability.toDouble()) / child.visits)
            if (value > bestValue) {
                bestValue = value
                best = action to child
            }
        }
        return best!!
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Uma desafiante com vantagem média [mean], erro-padrão [standardError] e [visits] visitas pode trocar a
 * preferida do Médio? Exige [HardBotConfig.minVisitsToOverride] visitas, vantagem acima de
 * [HardBotConfig.overrideMargin] e, se [HardBotConfig.overrideConfidence] > 0, acima desse número de
 * erros-padrão. Com confiança 0 o teste do erro-padrão é desligado de fato (sem `0 × ∞ = NaN`).
 */
internal fun passesOverride(mean: Double, standardError: Double, visits: Int, config: HardBotConfig): Boolean {
    if (visits < config.minVisitsToOverride || mean <= config.overrideMargin) return false
    if (config.overrideConfidence == 0.0) return true
    return mean > config.overrideConfidence * standardError
}

/**
 * Chave canônica de uma ação: as cartas trocadas pela cópia 0 do mesmo valor e naipe. As duas cópias são
 * equivalentes para as regras, e o motor escolhe a cópia representativa conforme a mão, que varia de
 * mundo para mundo depois de comprar do monte.
 */
internal fun Action.key(): Action = when (this) {
    Action.DrawFromStock, Action.DeclineDraw -> this
    is Action.Discard -> Action.Discard(card.canonical())
    is Action.CreateMeld -> Action.CreateMeld(cards.map { it.canonical() })
    is Action.AddToMeld -> Action.AddToMeld(meldId, cards.map { it.canonical() })
    is Action.TakeDiscardPile -> Action.TakeDiscardPile(
        when (val plan = plan) {
            is DiscardPlan.NewMeld -> DiscardPlan.NewMeld(plan.handCards.map { it.canonical() })
            is DiscardPlan.AddToMeld -> DiscardPlan.AddToMeld(plan.meldId, plan.handCards.map { it.canonical() })
        },
    )
}

private fun Card.canonical(): Card = if (deck == 0) this else Card(rank, suit, 0)
