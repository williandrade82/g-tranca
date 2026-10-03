package com.gtranca.ai.hard

import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import kotlin.random.Random

/**
 * Simulação (rollout) do ISMCTS sobre um mundo sorteado: o Médio joga por todos os assentos.
 *
 * Cada assento tem o seu [MediumBot], com o histórico público real da partida ([PublicEvent]s já
 * observados) mais os eventos da própria simulação, e recebe só a sua vista do mundo sorteado
 * ([sampledView]). Os eventos são públicos (a compra do monte não revela a carta; as cartas levadas do lixo
 * estavam abertas, §5.6), então nenhuma política aprende cartas ocultas da determinização.
 */
internal object Rollout {

    /** Limite de ações por simulação (proteção contra laço; uma partida real tem bem menos). */
    private const val MAX_ACTIONS = 2_000

    /** Uma política (Médio) por assento, com o histórico público real até aqui. */
    fun policies(world: RoundState, random: Random, events: List<PublicEvent>): List<MediumBot> =
        world.mode.seats.map { seat ->
            MediumBot(Random(random.nextLong() + seat.index)).also { bot -> events.forEach(bot::observe) }
        }

    /** Aplica [action] do [seat] e avisa todas as políticas do evento público correspondente. */
    fun step(state: RoundState, seat: Seat, action: Action, policies: List<MediumBot>): RoundState {
        val event = PublicEvent.of(state.discardPile, seat, action)
        val next = RoundEngine.apply(state, seat, action)
        policies.forEach { it.observe(event) }
        return next
    }

    /**
     * Joga com as [policies] até a partida terminar ou até [maxTurns] passagens de vez e devolve a recompensa
     * ([Evaluation.reward]) do [side].
     */
    fun play(start: RoundState, policies: List<MediumBot>, maxTurns: Int, scale: Double, side: Side): Double {
        var state = start
        var turns = 0
        var actions = 0
        while (state.phase != Phase.FINISHED && turns < maxTurns && actions < MAX_ACTIONS) {
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            if (legal.isEmpty()) break
            val action = if (legal.size == 1) legal.single() else policies[seat.index].chooseAction(state.sampledView(seat), legal)
            state = step(state, seat, action, policies)
            if (state.currentSeat != seat) turns++
            actions++
        }
        return Evaluation.reward(state, side, scale)
    }
}
