package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.Seat

/**
 * §3.5, §6.5 os 3 vermelhos que cada assento baixou na **vez mais recente** dele, para o detalhe "última jogada" (junto
 * das ações públicas, [GameSnapshot.turnEvents]). Os do início da vez são baixados durante a ação do jogador anterior
 * (a passagem de vez), por isso ficam pendentes até o assento começar a jogar; os baixados durante a jogada (compra,
 * morto, reposição em cadeia) entram direto na vez do jogador.
 */
internal class TurnRedThrees(private val seatCount: Int) {
    /** Por assento ([Seat.index]), os 3 vermelhos baixados na vez mais recente (ou atual) dele, na ordem do registro. */
    var current: List<List<Card>> = List(seatCount) { emptyList() }
        private set

    /**
     * Por assento, para cada 3 vermelho de [current], quantas ações da vez já tinham acontecido quando ele foi baixado
     * (0 = início da vez, antes da compra; depois da compra = 1...): a ordem real da vez, a mesma das animações.
     */
    var currentAfter: List<List<Int>> = List(seatCount) { emptyList() }
        private set

    private var pending: List<List<Card>> = List(seatCount) { emptyList() }

    /** Assentos cuja vez já começou ([beginTurn]) antes da 1ª ação: a ação que abre a vez não apaga os 3 vermelhos dela. */
    private var begun: Set<Int> = emptySet()

    /**
     * §3.5 a vez passou ao [seat] (descarte do anterior ou distribuição): os 3 vermelhos do início da vez dele aparecem
     * já, antes da compra, e a vez anterior dele sai do detalhe.
     */
    fun beginTurn(seat: Seat) {
        current = current.replaceAt(seat.index, pending[seat.index])
        currentAfter = currentAfter.replaceAt(seat.index, List(pending[seat.index].size) { 0 })
        pending = pending.replaceAt(seat.index, emptyList())
        begun = begun + seat.index
    }

    /** Partida nova: nada nas vezes passadas; os 3 vermelhos já baixados (início da vez do 1º jogador) ficam pendentes. */
    fun reset(log: List<RedThreeLaid>) {
        current = List(seatCount) { emptyList() }
        currentAfter = List(seatCount) { emptyList() }
        begun = emptySet()
        pending = List(seatCount) { seat -> log.filter { it.seat.index == seat && it.atTurnStart }.map { it.card } }
    }

    /**
     * O [actor] jogou uma ação que abre a vez dele ([startsTurn]: comprar, recusar ou pegar o lixo) ou continua a vez
     * dele, e o motor registrou [newEntries] nessa ação.
     */
    fun record(actor: Seat, startsTurn: Boolean, newEntries: List<RedThreeLaid>, eventsInTurn: Int = 0) {
        if (startsTurn && actor.index in begun) {
            begun = begun - actor.index
        } else if (startsTurn) {
            current = current.replaceAt(actor.index, pending[actor.index])
            currentAfter = currentAfter.replaceAt(actor.index, List(pending[actor.index].size) { 0 })
            pending = pending.replaceAt(actor.index, emptyList())
        }
        for (entry in newEntries) {
            val seat = entry.seat.index
            if (entry.atTurnStart) {
                // do início da vez de outro assento: espera ele começar a jogar
                pending = pending.replaceAt(seat, pending[seat] + entry.card)
            } else {
                current = current.replaceAt(seat, current[seat] + entry.card)
                // Baixado nesta ação (compra, morto, reposição em cadeia): depois das [eventsInTurn] ações da vez.
                currentAfter = currentAfter.replaceAt(seat, currentAfter[seat] + eventsInTurn)
            }
        }
    }

    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
}
