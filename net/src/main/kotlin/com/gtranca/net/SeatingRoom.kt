package com.gtranca.net

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Quem ocupa um assento na escolha de posições (§16.2): um humano (id público) ou um robô (id do personagem, §14.5). */
@Serializable
sealed interface SeatOccupant {
    @Serializable
    @SerialName("h")
    data class Human(val playerId: Int) : SeatOccupant

    @Serializable
    @SerialName("r")
    data class Robot(val persona: String) : SeatOccupant
}

/**
 * Direção da rotação (§16.2). [RIGHT] procura a partir do assento seguinte na ordem de jogada (§4.2, índice crescente, o
 * jogador à direita); [LEFT], do anterior.
 */
@Serializable
enum class RotateDirection(val step: Int) { LEFT(-1), RIGHT(+1) }

/**
 * Estado **público** da escolha de posições (§16.2), igual em todos os aparelhos: o ocupante de cada assento, quem já
 * confirmou e se as posições travaram. Não há assento vago: todo assento sem humano tem o seu robô. Nada oculto (sem
 * semente).
 */
@Serializable
data class SeatingState(
    val mode: GameMode,
    val occupants: List<SeatOccupant>,
    /** Índices dos assentos confirmados. */
    val confirmed: Set<Int> = emptySet(),
    /** §16.2 todos confirmaram: o jogo começa 2 s depois e a mesa sai da lista. */
    val locked: Boolean = false,
) {
    init {
        require(occupants.size == mode.seatCount) { "Ocupantes: ${occupants.size}; assentos: ${mode.seatCount}" }
    }

    fun seatOf(playerId: Int): Seat? =
        occupants.indexOfFirst { it == SeatOccupant.Human(playerId) }.takeIf { it >= 0 }?.let(::Seat)

    fun isConfirmed(seat: Seat): Boolean = seat.index in confirmed

    val humans: List<Int> get() = occupants.filterIsInstance<SeatOccupant.Human>().map { it.playerId }

    /** §16.2 personagem de cada assento de robô (índice → id): a única fonte dos robôs no início do jogo. */
    val robots: Map<Int, String>
        get() = occupants.withIndex().mapNotNull { (i, o) -> (o as? SeatOccupant.Robot)?.let { i to it.persona } }.toMap()

    /** §16.2 a mesa aparece na lista dos jogadores próximos: ainda na escolha e com ao menos um robô. */
    val listed: Boolean get() = !locked && occupants.any { it is SeatOccupant.Robot }

    /**
     * §16.2 com quem o jogador [playerId] trocaria ao rotacionar para [direction]: o próximo ocupante naquela direção
     * (humano ou robô) que **não** confirmou, pulando os confirmados; `null` = rotação indisponível (travado, ele já
     * confirmou ou não há ninguém).
     */
    fun rotationTarget(playerId: Int, direction: RotateDirection): Seat? {
        val mine = seatOf(playerId) ?: return null
        if (locked || isConfirmed(mine)) return null
        val n = mode.seatCount
        return (1 until n).asSequence()
            .map { Seat(Math.floorMod(mine.index + direction.step * it, n)) }
            .firstOrNull { !isConfirmed(it) }
    }

    /** §16.2 rotaciona: troca de lugar com [rotationTarget]; `null` se indisponível. */
    fun rotate(playerId: Int, direction: RotateDirection): SeatingState? {
        val target = rotationTarget(playerId, direction) ?: return null
        val mine = seatOf(playerId)!!
        val list = occupants.toMutableList()
        list[mine.index] = occupants[target.index]
        list[target.index] = occupants[mine.index]
        return copy(occupants = list)
    }

    /**
     * §16.2 o jogador confirma a posição (trava o lugar). Quando **todos os humanos** confirmaram, os robôs confirmam
     * sozinhos e as posições travam. `null` se ele não está na mesa, já confirmou ou travou.
     */
    fun confirm(playerId: Int): SeatingState? {
        val mine = seatOf(playerId) ?: return null
        if (locked || isConfirmed(mine)) return null
        return copy(confirmed = confirmed + mine.index).lockIfReady()
    }

    /** Trava quando todos os humanos confirmaram (robôs confirmam sozinhos, §16.2). */
    internal fun lockIfReady(): SeatingState {
        if (locked) return this
        val humanSeats = occupants.indices.filter { occupants[it] is SeatOccupant.Human }
        if (humanSeats.isEmpty() || !humanSeats.all { it in confirmed }) return this
        return copy(confirmed = occupants.indices.toSet(), locked = true)
    }

    companion object {
        /**
         * §14.4, §14.5, §16.2 sala inicial: os [participants] (o anfitrião primeiro; humanos e robôs escolhidos) ocupam os
         * assentos na ordem; os que sobram recebem personagens sorteados com [random] entre os do [personaPool] não usados.
         */
        fun create(mode: GameMode, participants: List<SeatOccupant>, personaPool: List<String>, random: Random): SeatingState {
            require(participants.size in 1..mode.seatCount) { "Participantes demais para o modo" }
            val list = participants.toMutableList()
            while (list.size < mode.seatCount) list += SeatOccupant.Robot(drawPersona(list, personaPool, random))
            return SeatingState(mode, list)
        }

        /** §14.5 personagem sorteado entre os do [pool] que não estão na mesa. */
        fun drawPersona(occupants: List<SeatOccupant>, pool: List<String>, random: Random): String {
            val used = occupants.filterIsInstance<SeatOccupant.Robot>().map { it.persona }.toSet()
            val free = pool.filter { it !in used }
            require(free.isNotEmpty()) { "Sem personagem livre" }
            return free[random.nextInt(free.size)]
        }
    }
}

/**
 * Sala de escolha de posições do **anfitrião** (§16.2): o [state] público mais o que só ele sabe (o conjunto de
 * personagens e o sorteio com a semente da mesa, que nunca trafega). Imutável: cada operação devolve a sala nova, ou
 * `null` quando não se aplica.
 */
class SeatingRoom private constructor(
    val state: SeatingState,
    private val personaPool: List<String>,
    private val seed: Long,
    private val draws: Int,
) {
    private fun with(next: SeatingState, drew: Boolean = false) =
        SeatingRoom(next, personaPool, seed, if (drew) draws + 1 else draws)

    private fun nextRandom(): Random = randomFor(seed, draws)

    fun rotate(playerId: Int, direction: RotateDirection): SeatingRoom? = state.rotate(playerId, direction)?.let(::with)

    fun confirm(playerId: Int): SeatingRoom? = state.confirm(playerId)?.let(::with)

    /**
     * §16.2 o humano [playerId] saiu antes do início (recusou o convite, não estava ao alcance, saldo insuficiente ou
     * abandonou): um robô sorteado entre os não usados assume o lugar. Se ele era o último humano sem confirmar, as
     * posições travam. `null` se ele não está na mesa ou já travou.
     */
    fun replaceWithRobot(playerId: Int): SeatingRoom? {
        val seat = state.seatOf(playerId) ?: return null
        if (state.locked) return null
        val persona = SeatingState.drawPersona(state.occupants, personaPool, nextRandom())
        val list = state.occupants.toMutableList().also { it[seat.index] = SeatOccupant.Robot(persona) }
        return with(state.copy(occupants = list, confirmed = state.confirmed - seat.index).lockIfReady(), drew = true)
    }

    /**
     * §16.2 pedido de entrada aceito: o novo jogador entra no lugar do **primeiro robô** na ordem dos assentos (sem
     * confirmar). `null` se não há robô (mesa cheia) ou já travou.
     */
    fun admit(playerId: Int): SeatingRoom? {
        if (state.locked || state.seatOf(playerId) != null) return null
        val index = state.occupants.indexOfFirst { it is SeatOccupant.Robot }.takeIf { it >= 0 } ?: return null
        val list = state.occupants.toMutableList().also { it[index] = SeatOccupant.Human(playerId) }
        return with(state.copy(occupants = list, confirmed = state.confirmed - index))
    }

    companion object {
        fun create(mode: GameMode, participants: List<SeatOccupant>, personaPool: List<String>, seed: Long): SeatingRoom {
            val state = SeatingState.create(mode, participants, personaPool, randomFor(seed, 0))
            return SeatingRoom(state, personaPool, seed, 1)
        }

        private fun randomFor(seed: Long, draws: Int): Random = Random(seed * 31 + draws)

        /** Sala de uma mesa já existente (ex.: anfitrião que assume); o próximo sorteio continua de [draws]. */
        fun of(state: SeatingState, personaPool: List<String>, seed: Long, draws: Int = 1): SeatingRoom =
            SeatingRoom(state, personaPool, seed, draws)
    }
}
