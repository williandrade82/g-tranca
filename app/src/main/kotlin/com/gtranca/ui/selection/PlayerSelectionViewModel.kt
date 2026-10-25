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
enum class SelectionNotice { PICK_SEAT_FIRST, CANNOT_CALL }

/** O que a tela desenha, além dos lugares: o lugar escolhido, o que está sendo movido e o menu aberto. */
data class SelectionUi(
    val layout: TableLayout,
    /** Lugar VAGO escolhido, à espera de um robô ou jogador. */
    val selectedSeat: Int? = null,
    /** Alternativa ao arrastar: o lugar de origem de um "Mover" à espera do destino. */
    val movingFrom: Int? = null,
    /** Lugar preenchido com o menu (Remover / Mover) aberto. */
    val menuSeat: Int? = null,
)

/**
 * *Seleção de jogadores* (§14.4, §16.2, docs/telas.md 3.3): a mesa de lugares — o jogador no lugar 0 e os demais VAGO —
 * que se preenche tocando num lugar e depois num robô ou jogador próximo livre, remove, move/troca (arrastar ou o Mover
 * por toque) e completa com robôs. Com o Wi-Fi do jogo ligado os lugares vivem no `Recruitment` ([attach]); sem ele, aqui.
 */
class PlayerSelectionViewModel(
    val config: GameConfig,
    /** Semente da mesa: sorteio de "Completar com robôs" e do jogo. */
    val seed: Long = Random.nextLong(),
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

    private fun setLayout(next: TableLayout) = _ui.update { it.copy(layout = next) }

    private fun clearPicks() = _ui.update { it.copy(selectedSeat = null, movingFrom = null, menuSeat = null) }

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
        clearPicks()
    }

    /** §16.2 os lugares mudaram na rede (a única fonte quando o recrutamento está aberto). */
    fun onRemoteLayout(next: TableLayout) {
        if (recruiter == null && next == layout) return
        _ui.update { st ->
            // quem foi para VAGO deixa de estar escolhido
            val seatGone = st.selectedSeat?.takeIf { next.slots.getOrNull(it) != Slot.Vacant }
            st.copy(layout = next, selectedSeat = st.selectedSeat.takeIf { it != seatGone }, menuSeat = st.menuSeat?.takeIf { next.slots.getOrNull(it)?.let { s -> s != Slot.Vacant } == true })
        }
    }

    private fun networked(): SeatRecruiter? = recruiter

    private fun <T> run(block: suspend () -> T) {
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

    // ---- toques ---------------------------------------------------------------------------------------------------

    /** Tocar num lugar: VAGO o escolhe (ou, no "Mover", recebe quem se move); preenchido abre o menu (ou recebe o "Mover"). */
    fun onSeatTap(seat: Int) {
        val st = _ui.value
        if (seat == TableLayout.HOST_SEAT) return clearPicks()
        val from = st.movingFrom
        if (from != null) {
            if (seat != from) move(from, seat)
            return clearPicks()
        }
        if (st.layout.slots.getOrNull(seat) == null) return
        if (st.layout.slots[seat] == Slot.Vacant) {
            _ui.update { it.copy(selectedSeat = if (it.selectedSeat == seat) null else seat, menuSeat = null) }
        } else {
            _ui.update { it.copy(menuSeat = seat, selectedSeat = null) }
        }
    }

    /** Tocar num robô da lista: põe no lugar VAGO escolhido (sem lugar escolhido, só um aviso). */
    fun onRobotTap(persona: String) {
        val seat = _ui.value.selectedSeat
        if (seat == null) {
            _notices.tryEmit(SelectionNotice.PICK_SEAT_FIRST)
            return
        }
        if (persona in layout.usedPersonas) return
        clearPicks()
        val r = networked()
        if (r != null) run { r.placeRobot(seat, persona) } else layout.placeRobot(seat, persona)?.let(::setLayout)
    }

    /** Tocar num jogador próximo livre: chama para o lugar VAGO escolhido (só com o Wi-Fi do jogo ligado). */
    fun onPlayerTap(presence: PresenceAdvert) {
        val seat = _ui.value.selectedSeat
        if (seat == null) {
            _notices.tryEmit(SelectionNotice.PICK_SEAT_FIRST)
            return
        }
        clearPicks()
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

    // ---- menu / mover / completar -------------------------------------------------------------------------------

    fun onDismissMenu() = _ui.update { it.copy(menuSeat = null) }

    fun onRemove(seat: Int) {
        clearPicks()
        val r = networked()
        if (r != null) run { r.removeSeat(seat) } else layout.remove(seat)?.let(::setLayout)
    }

    /** Alternativa ao arrastar: começa um "Mover" e espera o toque no lugar de destino. */
    fun onMoveStart(seat: Int) = _ui.update { it.copy(movingFrom = seat, menuSeat = null, selectedSeat = null) }

    /** Soltar o avatar de [from] sobre [to] (ou tocar no destino do "Mover"): ocupado → troca; VAGO → move. */
    fun move(from: Int, to: Int) {
        if (from == to || from == TableLayout.HOST_SEAT || to == TableLayout.HOST_SEAT) return
        val r = networked()
        if (r != null) run { r.move(from, to) } else layout.move(from, to)?.let(::setLayout)
        clearPicks()
    }

    fun onFill() {
        clearPicks()
        val r = networked()
        if (r != null) run { r.fillWithRobots(Tables.personaPool, seed) } else setLayout(layout.fillWithRobots(Tables.personaPool, Random(seed)))
    }
}
