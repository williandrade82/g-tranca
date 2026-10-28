package com.gtranca.net

import com.gtranca.engine.model.GameMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Quem ocupa um lugar na *Seleção de jogadores* (§16.2). Nada oculto: só nome, personagem e chave de presença. */
@Serializable
sealed interface Slot {
    /** Lugar sem ninguém ("VAGO"). */
    @Serializable
    @SerialName("vacant")
    data object Vacant : Slot

    /** O recrutador (lugar 0, embaixo): fixo. */
    @Serializable
    @SerialName("host")
    data object Host : Slot

    /** Um robô, pelo id do personagem (§14.5). */
    @Serializable
    @SerialName("robot")
    data class Robot(val persona: String) : Slot

    /** Jogador próximo que recebeu o convite e ainda não respondeu (selo "Chamado"). */
    @Serializable
    @SerialName("called")
    data class Called(val key: String, val name: String) : Slot

    /** Jogador próximo que aceitou o convite (selo "Recrutado"). */
    @Serializable
    @SerialName("recruited")
    data class Recruited(val key: String, val name: String) : Slot
}

/** Relação de um lugar com o recrutador (lugar 0), para o texto do convite (§16.2). */
enum class SeatRelation { SELF, PARTNER, OPPONENT }

/**
 * §16.2 os lugares da mesa montada pelo recrutador, **puro** (sem rede): 2 lugares no individual (§16.3) ou 4 em duplas.
 * O lugar **0** é do recrutador, fixo e embaixo; em duplas o lugar **2** (o da frente) é o do parceiro. Toda operação
 * devolve o layout novo, ou `null` quando não se aplica (o layout não muda).
 */
@Serializable
data class TableLayout(val mode: GameMode, val slots: List<Slot>) {
    init {
        require(slots.size == mode.seatCount) { "Lugares: ${slots.size}; esperados: ${mode.seatCount}" }
        require(slots[HOST_SEAT] == Slot.Host && slots.drop(1).none { it == Slot.Host }) { "O lugar 0 é do recrutador" }
    }

    /** §16.2 relação do lugar com o recrutador: parceiro só o da frente em duplas; os demais, adversários. */
    fun relationOf(seat: Int): SeatRelation = when {
        seat == HOST_SEAT -> SeatRelation.SELF
        mode == GameMode.DUPLAS && seat == PARTNER_SEAT -> SeatRelation.PARTNER
        else -> SeatRelation.OPPONENT
    }

    /** Lugar do jogador remoto de chave [key] (chamado ou recrutado), ou `null`. */
    fun seatOf(key: String): Int? = slots.indexOfFirst { it.remoteKey() == key }.takeIf { it >= 0 }

    val usedPersonas: Set<String> get() = slots.filterIsInstance<Slot.Robot>().map { it.persona }.toSet()

    /** Personagem de cada lugar de robô (índice do lugar → id), a única fonte dos robôs ao começar (§14.5). */
    val robots: Map<Int, String>
        get() = slots.withIndex().mapNotNull { (i, s) -> (s as? Slot.Robot)?.let { i to it.persona } }.toMap()

    /** Jogadores remotos (chamados e recrutados) com o lugar. */
    val remotes: List<Pair<Int, Slot>> get() = slots.withIndex().filter { it.value.remoteKey() != null }.map { it.index to it.value }

    val isFull: Boolean get() = slots.none { it == Slot.Vacant }

    val hasCalled: Boolean get() = slots.any { it is Slot.Called }

    /** §16.2 "Começar Partida" só vale com todos os lugares preenchidos e nenhum Chamado pendente. */
    val canStart: Boolean get() = isFull && !hasCalled

    private fun with(seat: Int, slot: Slot): TableLayout = copy(slots = slots.toMutableList().also { it[seat] = slot })

    private fun validSeat(seat: Int) = seat in 0 until slots.size

    /**
     * §16.2, §16.4 a cópia que vai ao jogador [key]: só a **própria** chave de presença fica nos lugares; as dos outros
     * chamados/recrutados saem (vazias), para ninguém usar a chave alheia e tomar o lugar de outro. Nome e estado seguem.
     */
    fun redactedFor(key: String): TableLayout = copy(
        slots = slots.map {
            when (it) {
                is Slot.Called -> if (it.key == key) it else it.copy(key = "")
                is Slot.Recruited -> if (it.key == key) it else it.copy(key = "")
                else -> it
            }
        },
    )

    /** §16.2 chama o jogador [key] para o lugar [seat] (VAGO); cada jogador ocupa um lugar só. */
    fun call(seat: Int, key: String, name: String): TableLayout? =
        if (validSeat(seat) && slots[seat] == Slot.Vacant && seatOf(key) == null) with(seat, Slot.Called(key, name)) else null

    /** §14.5, §16.2 põe o robô [persona] no lugar [seat] (VAGO), se ele ainda não está na mesa. */
    fun placeRobot(seat: Int, persona: String): TableLayout? =
        if (validSeat(seat) && slots[seat] == Slot.Vacant && persona !in usedPersonas) with(seat, Slot.Robot(persona)) else null

    /** §16.2 remove quem está no lugar [seat] (nunca o 0): o lugar volta a VAGO. */
    fun remove(seat: Int): TableLayout? =
        if (validSeat(seat) && seat != HOST_SEAT && slots[seat] != Slot.Vacant) with(seat, Slot.Vacant) else null

    /**
     * §16.2 só o recrutador move gente: [from] -> [to]; **ocupado → troca**, **VAGO → move**. O lugar 0 não se move nem
     * recebe ninguém. Pode mover também um Chamado.
     */
    fun move(from: Int, to: Int): TableLayout? {
        if (!validSeat(from) || !validSeat(to) || from == to || from == HOST_SEAT || to == HOST_SEAT) return null
        if (slots[from] == Slot.Vacant) return null
        val list = slots.toMutableList()
        list[to] = slots[from]
        list[from] = slots[to]
        return copy(slots = list)
    }

    /** §16.2 o Chamado [key] aceitou: vira Recrutado. */
    fun accept(key: String): TableLayout? {
        val seat = seatOf(key) ?: return null
        val slot = slots[seat] as? Slot.Called ?: return null
        return with(seat, Slot.Recruited(slot.key, slot.name))
    }

    /** §16.2 o jogador [key] recusou, saiu, foi removido ou sumiu: o lugar volta a VAGO. */
    fun vacate(key: String): TableLayout? = seatOf(key)?.let { with(it, Slot.Vacant) }

    /**
     * §14.5, §16.2 "Completar com robôs": sorteia, com [random] (a semente do recrutador), personagens do [pool] **ainda
     * não usados** para os lugares VAGO, na ordem dos lugares. Sem personagem livre para todos, preenche os que der.
     */
    fun fillWithRobots(pool: List<String>, random: Random): TableLayout {
        val free = pool.filter { it !in usedPersonas }.distinct().shuffled(random).iterator()
        val list = slots.toMutableList()
        for (i in list.indices) if (list[i] == Slot.Vacant && free.hasNext()) list[i] = Slot.Robot(free.next())
        return copy(slots = list)
    }

    companion object {
        const val HOST_SEAT: Int = 0

        /** O lugar da frente do recrutador em duplas (§1.1). */
        const val PARTNER_SEAT: Int = 2

        /** §16.2 mesa nova: o recrutador no lugar 0 e todos os outros VAGO. */
        fun initial(mode: GameMode): TableLayout =
            TableLayout(mode, List(mode.seatCount) { if (it == HOST_SEAT) Slot.Host else Slot.Vacant })
    }
}

/** Chave de presença de quem ocupa o lugar (chamado ou recrutado); `null` para os demais. */
fun Slot.remoteKey(): String? = when (this) {
    is Slot.Called -> key
    is Slot.Recruited -> key
    else -> null
}
