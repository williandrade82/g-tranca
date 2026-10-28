package com.gtranca.ui.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.game.GameConfig
import com.gtranca.game.Tables
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.Slot
import com.gtranca.net.TableLayout
import com.gtranca.net.remoteKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * §16.2 as operações de recrutamento que a *Seleção de jogadores* usa quando o Wi-Fi do jogo está ligado (o `Recruitment`
 * do `:net`); fora da rede os lugares são só locais. Interface para testar na JVM.
 */
interface SeatRecruiter {
    /** Abre a mesa de lugares na rede (os lugares começam VAGO). */
    suspend fun open()

    suspend fun call(seat: Int, presence: PresenceAdvert): Boolean

    suspend fun placeRobot(seat: Int, persona: String): Boolean

    suspend fun removeSeat(seat: Int): Boolean

    suspend fun move(from: Int, to: Int): Boolean

    suspend fun fillWithRobots(pool: List<String>, seed: Long)
}

/** Avisos breves da *Seleção de jogadores*. */
enum class SelectionNotice { CANNOT_CALL }

/** O que está escolhido, à espera do passo seguinte (docs/telas.md 3.3): um lugar, um robô da lista ou um jogador da lista. */
sealed interface Selection {
    data class Seat(val index: Int) : Selection

    data class Robot(val persona: String) : Selection

    data class Player(val presence: PresenceAdvert) : Selection
}

/** O que a tela desenha, além dos lugares: a seleção pendente e quando cada Chamado recebeu o convite. */
data class SelectionUi(
    val layout: TableLayout,
    val selected: Selection? = null,
    /** Relógio local (ms) em que cada jogador Chamado passou a estar nos lugares: o anel de contagem do convite (§16.15). */
    val calledAt: Map<String, Long> = emptyMap(),
)

/**
 * *Seleção de jogadores* (§14.4, §16.2, docs/telas.md 3.3): a mesa de lugares — o jogador no lugar 0 e os demais VAGO.
 *
 * A interação é por **seleção em dois toques, nos dois sentidos**: um item da lista (robô ou jogador próximo livre) e um
 * lugar VAGO — em qualquer ordem — colocam o robô ou chamam o jogador (o convite só sai com os dois escolhidos); dois
 * lugares trocam de lugar (VAGO ↔ ocupado move; nunca o do jogador); um lugar ocupado e depois um item da lista **retira**
 * quem estava no lugar (sem pôr o item no lugar). Arrastar e soltar um avatar faz o mesmo que escolher dois lugares.
 * Com o Wi-Fi do jogo ligado os lugares vivem no `Recruitment` ([attach]); sem ele, aqui.
 */
class PlayerSelectionViewModel(
    val config: GameConfig,
    /** Semente da mesa: sorteio de "Completar com robôs" e do jogo. */
    val seed: Long = Random.nextLong(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {
    private val _ui = MutableStateFlow(SelectionUi(TableLayout.initial(config.mode)))
    val ui: StateFlow<SelectionUi> = _ui.asStateFlow()

    private val _notices = MutableSharedFlow<SelectionNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<SelectionNotice> = _notices.asSharedFlow()

    @Volatile
    private var recruiter: SeatRecruiter? = null

    val layout: TableLayout get() = _ui.value.layout

    val canStart: Boolean get() = layout.canStart

    /** Há jogador remoto (chamado ou recrutado) nos lugares: o jogo é em rede. */
    val hasRemotes: Boolean get() = layout.remotes.isNotEmpty()

    /** Troca o layout e atualiza o relógio dos Chamados (novos recebem agora; quem saiu perde o registro). */
    private fun setLayout(next: TableLayout) = _ui.update { st ->
        val called = next.slots.filterIsInstance<Slot.Called>().map { it.key }
        val at = called.associateWith { st.calledAt[it] ?: clock() }
        st.copy(layout = next, calledAt = at, selected = st.selected?.takeIf { validIn(it, next) })
    }

    /** A seleção pendente ainda faz sentido no layout [next] (o lugar existe e não é o do jogador; o robô não sentou). */
    private fun validIn(sel: Selection, next: TableLayout): Boolean = when (sel) {
        is Selection.Seat -> sel.index in 1 until next.slots.size
        is Selection.Robot -> sel.persona !in next.usedPersonas
        is Selection.Player -> next.seatOf(sel.presence.key) == null
    }

    private fun select(sel: Selection?) = _ui.update { it.copy(selected = sel) }

    /**
     * §16.2 o Wi-Fi do jogo ligou: abre a mesa na rede e repete nela o que já estava nos lugares (robôs), para ligar não
     * perder a seleção.
     */
    suspend fun attach(r: SeatRecruiter) {
        val local = layout
        r.open()
        local.robots.forEach { (seat, persona) -> r.placeRobot(seat, persona) }
        recruiter = r
    }

    /** §16.2 o Wi-Fi do jogo desligou: chamados e recrutados saem dos lugares (VAGO); os robôs ficam. */
    fun detach() {
        if (recruiter == null) return
        recruiter = null
        val kept = layout.slots.map { if (it.remoteKey() != null) Slot.Vacant else it }
        setLayout(TableLayout(layout.mode, kept))
        select(null)
    }

    /** §16.2 os lugares mudaram na rede (a única fonte quando o recrutamento está aberto). */
    fun onRemoteLayout(next: TableLayout) {
        if (recruiter == null && next == layout) return
        setLayout(next)
    }

    private fun networked(): SeatRecruiter? = recruiter

    private fun launchOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notices.tryEmit(SelectionNotice.CANNOT_CALL)
            }
        }
    }

    // ---- operações ------------------------------------------------------------------------------------------------

    private fun place(seat: Int, persona: String) {
        select(null)
        val r = networked()
        if (r != null) launchOp { r.placeRobot(seat, persona) } else layout.placeRobot(seat, persona)?.let(::setLayout)
    }

    private fun call(seat: Int, presence: PresenceAdvert) {
        select(null)
        val r = networked()
        if (r == null) {
            _notices.tryEmit(SelectionNotice.CANNOT_CALL)
            return
        }
        viewModelScope.launch {
            val ok = runCatching { r.call(seat, presence) }.getOrDefault(false)
            if (!ok) _notices.tryEmit(SelectionNotice.CANNOT_CALL)
        }
    }

    private fun remove(seat: Int) {
        select(null)
        val r = networked()
        if (r != null) launchOp { r.removeSeat(seat) } else layout.remove(seat)?.let(::setLayout)
    }

    /** §16.2 troca (ocupado) ou move (VAGO): só o recrutador; o lugar 0 não se move nem recebe ninguém. */
    fun move(from: Int, to: Int) {
        select(null)
        if (from == to || from == TableLayout.HOST_SEAT || to == TableLayout.HOST_SEAT) return
        // VAGO ↔ ocupado é sempre "mover o ocupado para o VAGO", qualquer que tenha sido o lugar tocado primeiro.
        val (a, b) = if (layout.slots.getOrNull(from) == Slot.Vacant && layout.slots.getOrNull(to) != Slot.Vacant) to to from else from to to
        val r = networked()
        if (r != null) launchOp { r.move(a, b) } else layout.move(a, b)?.let(::setLayout)
    }

    // ---- toques ---------------------------------------------------------------------------------------------------

    private fun isVacant(seat: Int) = layout.slots.getOrNull(seat) == Slot.Vacant

    private fun isFilled(seat: Int) = layout.slots.getOrNull(seat)?.let { it != Slot.Vacant && it != Slot.Host } == true

    /**
     * Tocar num lugar. O do jogador não faz nada (cancela a seleção). Com um robô ou jogador da lista escolhido, o lugar
     * VAGO o recebe (robô colocado, jogador chamado); com outro lugar escolhido, os dois trocam.
     */
    fun onSeatTap(seat: Int) {
        if (seat == TableLayout.HOST_SEAT || seat !in layout.slots.indices) return select(null)
        when (val sel = _ui.value.selected) {
            null -> select(Selection.Seat(seat))
            is Selection.Seat -> when {
                sel.index == seat -> select(null)
                // dois VAGO: nada a trocar, vale o novo
                isVacant(sel.index) && isVacant(seat) -> select(Selection.Seat(seat))
                else -> move(sel.index, seat)
            }
            is Selection.Robot -> if (isVacant(seat)) place(seat, sel.persona) else select(Selection.Seat(seat))
            is Selection.Player -> if (isVacant(seat)) call(seat, sel.presence) else select(Selection.Seat(seat))
        }
    }

    /**
     * Tocar num robô da lista. Com um lugar VAGO escolhido, o robô é colocado; com um lugar **ocupado** escolhido, esse
     * ocupante é **retirado** (o robô tocado não entra). Sem seleção, escolhe o robô (se ele ainda não está sentado).
     */
    fun onRobotTap(persona: String) {
        val seated = persona in layout.usedPersonas
        when (val sel = _ui.value.selected) {
            is Selection.Seat -> when {
                isFilled(sel.index) -> remove(sel.index)
                !seated -> place(sel.index, persona)
            }
            is Selection.Robot -> select(if (sel.persona == persona) null else if (!seated) Selection.Robot(persona) else null)
            else -> if (!seated) select(Selection.Robot(persona))
        }
    }

    /**
     * Tocar num jogador próximo livre da lista (só com o Wi-Fi do jogo ligado). Com um lugar VAGO escolhido, ele é chamado;
     * com um lugar ocupado escolhido, esse ocupante é retirado (o jogador tocado não é chamado). Sem seleção, escolhe-o.
     */
    fun onPlayerTap(presence: PresenceAdvert) {
        val onTable = layout.seatOf(presence.key) != null
        when (val sel = _ui.value.selected) {
            is Selection.Seat -> when {
                isFilled(sel.index) -> remove(sel.index)
                !onTable -> call(sel.index, presence)
            }
            is Selection.Player -> select(if (sel.presence.key == presence.key) null else if (!onTable) Selection.Player(presence) else null)
            else -> if (!onTable) select(Selection.Player(presence))
        }
    }

    /** Tocar numa área vazia (ou de novo no mesmo item): cancela a seleção pendente. */
    fun onClear() = select(null)

    fun onFill() {
        select(null)
        val r = networked()
        if (r != null) launchOp { r.fillWithRobots(Tables.personaPool, seed) } else setLayout(layout.fillWithRobots(Tables.personaPool, Random(seed)))
    }
}
