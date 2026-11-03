package com.gtranca.online.lobby

import com.gtranca.online.contracts.AdminStatsDto
import com.gtranca.online.contracts.AdminTableSummary
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.Visibilidade
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

/** §18.2: opcoes fixas de toda mesa do servidor. `"{}"` = perfil Classica (RuleSet padrao do motor). */
val SERVER_TABLE_RULES = TableRules(
    mode = TableMode.DUPLAS,
    rulesJson = "{}",
    targetScore = 1500,
    bet = 50,
    skillBoostersAllowed = true,
    tempoDeJogada = TempoDeJogada.QUINZE_MINUTOS,
    punicao = Punicao.PERDE_APOSTA,
    visibilidade = Visibilidade.PRIVADA,
    robotLevel = BotLevel.FACIL,
)

data class LobbyConfig(
    /** Mesa ABERTA cujos humanos ficaram sem atividade por isto volta a ser uma mesa aberta vazia. */
    val openTtlMs: Long = 30 * 60_000L,
    val closedRetentionMs: Long = 10 * 60_000L,
    val playingTtlMs: Long = 6 * 3_600_000L,
    /** §18.2: depois que o 1o jogador entra numa mesa, o servidor abre a proxima mesa passado este tempo. */
    val nextTableDelayMs: Long = 5 * 60_000L,
    val serverRules: TableRules = SERVER_TABLE_RULES,
)

/**
 * Mesas mantidas pelo servidor (§18.2, §18.4). O jogador nao cria mesa: o servidor mantem sempre uma aberta, abre a
 * proxima 5 min depois que o 1o jogador entra (antecipado quando nao sobra mesa com lugar) e nunca guarda mais de uma
 * aberta vazia. O anfitriao e o 1o a entrar; Entregar a mesa e a sucessao seguem a ordem de entrada (§16.10).
 *
 * As decisoes do conjunto de mesas usam uma trava unica (`lock`): o lobby roda com uma replica so. SALDO: sem
 * economy-service, a aposta NAO e verificada ao entrar; [ErrorCode.SALDO_INSUFICIENTE] nunca e devolvido.
 */
class LobbyService(
    private val store: TableStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val config: LobbyConfig = LobbyConfig(),
    /** Versao da foto do convidado (§18.2), para o caminho da foto em cada lugar. */
    private val photoVersion: (String) -> Int? = { null },
) {
    private val lock = Any()

    private fun Table.toView() = toView(photoVersion)

    /** Quando abrir a proxima mesa por tempo (`null` = sem contagem). */
    private var nextOpenAt: Long? = null

    /**
     * §18.7: o instante (relogio do servidor) de cada partida encerrada, para contar "na ultima hora" no Painel.
     * So em memoria (zera ao reiniciar o pod; nos primeiros 60 min depois de um reinicio o numero fica menor que o
     * real) - preferido a inventar uma contagem a partir das mesas ENCERRADA, que somem do [store] poucos minutos
     * depois (ver [LobbyConfig.closedRetentionMs]) e teriam o id reaproveitado.
     */
    private val matchEndedTimestamps = ConcurrentLinkedDeque<Long>()

    /** §18.2: nesta fase o jogador nao cria mesa. */
    @Suppress("UNUSED_PARAMETER")
    fun createTable(host: Guest, rules: TableRules): TableView =
        lobbyError(ErrorCode.SEM_PERMISSAO, "Nesta versão as mesas são do servidor: escolha uma na lista.", 403)

    /** Lista (§18.2): abertas primeiro, depois em jogo; encerradas nao aparecem. */
    fun list(onlinePlayers: Int): TableListView = synchronized(lock) {
        maintainLocked()
        val tables = store.all()
            .filter { it.state != TableState.ENCERRADA }
            .sortedWith(compareBy<Table> { if (it.state == TableState.ABERTA) 0 else 1 }.thenBy { it.createdMs }.thenBy { it.id })
        TableListView(onlinePlayers, tables.map { it.toView() })
    }

    /** Humanos conectados nas mesas em jogo (segundo o game-service). */
    fun connectedInGame(): Set<String> = synchronized(lock) {
        store.all().filter { it.state == TableState.EM_JOGO }.flatMap { it.connected - it.abandoned }.toSet()
    }

    /** Roda os prazos (abrir a proxima mesa, limpar mesas velhas). Chamado periodicamente pelo servidor. */
    fun maintain() = synchronized(lock) { maintainLocked() }

    /** Consulta; quem esta sentado renova a atividade da mesa aberta. */
    fun get(guest: Guest, rawId: String): TableView = synchronized(lock) {
        maintainLocked()
        val table = store.update(idOf(rawId)) { t ->
            if (t.state == TableState.ABERTA && t.seatOf(guest.id) >= 0) t.touched(clock()) else t
        } ?: missing()
        table.toView()
    }

    fun join(guest: Guest, rawId: String, seat: Int?): TableView = mutate(rawId) { t ->
        requireOpen(t)
        if (t.seatOf(guest.id) >= 0) return@mutate t // idempotente
        val elsewhere = store.all().firstOrNull { it.id != t.id && it.state == TableState.ABERTA && it.seatOf(guest.id) >= 0 }
        if (elsewhere != null) {
            lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Você já está na mesa ${elsewhere.id}. Saia dela antes.", 409)
        }
        val target = if (seat != null) {
            checkSeat(t, seat)
            seat
        } else {
            t.seats.indexOfFirst { it is Seat.Vago }
        }
        if (target < 0) lobbyError(ErrorCode.MESA_CHEIA, "A mesa está cheia.", 409)
        if (t.seats[target] !is Seat.Vago) lobbyError(ErrorCode.MESA_CHEIA, "Esse lugar já está ocupado.", 409)
        val first = t.humans() == 0
        if (first && nextOpenAt == null) nextOpenAt = clock() + config.nextTableDelayMs // §18.2: conta 5 min
        t.withSeat(target, Seat.Humano(guest.id, guest.name)).copy(
            host = if (first) guest else t.host,
            entryOrder = t.entryOrder + guest.id,
        )
    }

    /** Trocar de lugar antes do jogo (§18.2): só para um lugar VAGO; o primeiro pedido vence. Anfitrião não muda. */
    fun move(guest: Guest, rawId: String, seat: Int): TableView = mutate(rawId) { t ->
        requireOpen(t)
        val from = t.seatOf(guest.id)
        if (from < 0) lobbyError(ErrorCode.LUGAR_INVALIDO, "Você não está nesta mesa.", 409)
        checkSeat(t, seat)
        if (from == seat) return@mutate t
        if (t.seats[seat] !is Seat.Vago) lobbyError(ErrorCode.MESA_CHEIA, "Esse lugar já está ocupado.", 409)
        val me = t.seats[from]
        t.withSeat(from, Seat.Vago).withSeat(seat, me)
    }

    /** Sair antes do jogo; o anfitriao tambem pode (o cargo passa ao proximo pela ordem de entrada, §18.2). */
    fun leave(guest: Guest, rawId: String): TableView = mutate(rawId) { t ->
        requireOpen(t)
        val idx = t.seatOf(guest.id)
        if (idx < 0) t else t.vacate(idx)
    }

    fun removeHuman(guest: Guest, rawId: String, seat: Int): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        requireOpen(t)
        checkSeat(t, seat)
        val s = t.seats[seat]
        if (s !is Seat.Humano || s.guestId == guest.id) {
            lobbyError(ErrorCode.LUGAR_INVALIDO, "Esse lugar não tem um jogador para remover.", 409)
        }
        t.vacate(seat)
    }

    /** Entregar a mesa (§18.2, §16.10): so o anfitriao, a um humano da mesa que esteja conectado. */
    fun passHost(guest: Guest, rawId: String, seat: Int): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        if (t.starting) lobbyError(ErrorCode.MESA_JA_INICIADA, "O jogo desta mesa está começando.", 409)
        if (t.state == TableState.ENCERRADA) lobbyError(ErrorCode.MESA_INEXISTENTE, "Esta mesa foi encerrada.", 404)
        checkSeat(t, seat)
        val s = t.seats[seat]
        if (s !is Seat.Humano || s.guestId == guest.id || s.guestId in t.abandoned) {
            lobbyError(ErrorCode.LUGAR_INVALIDO, "Escolha outro jogador da mesa.", 409)
        }
        if (!t.isConnected(s.guestId)) lobbyError(ErrorCode.LUGAR_INVALIDO, "Esse jogador não está conectado.", 409)
        t.copy(host = Guest(s.guestId, s.name))
    }

    /**
     * Passagem ao game-service (§18.3), em 3 tempos para NAO segurar a trava durante a chamada HTTP: [beginStart] valida,
     * poe robos nos lugares vagos (§18.2: o anfitriao inicia quando quiser) e marca `starting` (nada mais muda); o
     * chamador pede a partida; [commitStart] muda para EM_JOGO ou [abortStart] desfaz os robos e desmarca.
     */
    fun beginStart(guest: Guest, rawId: String): CreateMatchRequest {
        var plan: CreateMatchRequest? = null
        mutate(rawId) { t ->
            requireHost(t, guest)
            requireOpen(t)
            val profiles = BotProfile.entries.shuffled(random).iterator()
            val filled = t.seats.indices.filter { t.seats[it] is Seat.Vago }.toSet()
            val seats = t.seats.map { s -> if (s is Seat.Vago) Seat.Robo(profiles.next(), t.rules.robotLevel) else s }
            plan = CreateMatchRequest(
                tableId = t.id,
                rules = t.rules,
                seats = seats.map { s ->
                    when (s) {
                        is Seat.Humano -> SeatSpec.Human(s.guestId, s.name)
                        is Seat.Robo -> SeatSpec.Robot(s.profile, s.level)
                        Seat.Vago -> error("lugar vago depois de preencher")
                    }
                },
            )
            t.copy(seats = seats, starting = true, robotFilled = filled)
        }
        return plan!!
    }

    fun commitStart(rawId: String): TableView = mutate(rawId) { t ->
        if (t.starting) t.copy(state = TableState.EM_JOGO, starting = false, robotFilled = emptySet(), connected = t.humanIds()) else t
    }

    fun abortStart(rawId: String): TableView = mutate(rawId) { t ->
        if (!t.starting) return@mutate t
        t.copy(
            seats = t.seats.mapIndexed { i, s -> if (i in t.robotFilled) Seat.Vago else s },
            starting = false,
            robotFilled = emptySet(),
        )
    }

    /**
     * O game-service informa quem esta conectado e quem abandonou (§18.4). Se o anfitriao nao esta mais conectado (caiu,
     * desistiu), o cargo passa ao proximo humano conectado pela ordem de entrada, nunca a um robo (§18.2); sem ninguem,
     * a mesa fica sem anfitriao ate alguem poder assumir.
     */
    fun presence(rawId: String, connected: Set<String>, abandoned: Set<String>): TableView = mutate(rawId) { t ->
        if (t.state != TableState.EM_JOGO) return@mutate t
        t.copy(connected = connected, abandoned = t.abandoned + abandoned).reelectHost()
    }

    /** Antes de encerrar: so o anfitriao, e so com o jogo em andamento (§18.2). `true` = avisar o game-service antes. */
    fun closeNeedsGame(guest: Guest, rawId: String): Boolean = synchronized(lock) {
        maintainLocked()
        val t = store.find(idOf(rawId)) ?: missing()
        requireHost(t, guest)
        requireCanClose(t)
        t.state == TableState.EM_JOGO
    }

    /**
     * O game-service avisa que o jogo terminou ou foi encerrado (§18.4): a mesa EM_JOGO vira ENCERRADA. Idempotente; mesa
     * ABERTA nao e tocada (um aviso atrasado de outro jogo nao a fecha).
     */
    fun matchEnded(rawId: String): TableView {
        var wasInGame = false
        val view = mutate(rawId) { t ->
            if (t.state == TableState.EM_JOGO) {
                wasInGame = true
                t.copy(state = TableState.ENCERRADA)
            } else {
                t
            }
        }
        recordMatchEndedIfJustNow(wasInGame, view)
        return view
    }

    /**
     * §18.7: registra o fim de UMA partida, so quando [mutate] de fato terminou (nunca dentro da transformacao
     * que o [TableStore] pode tentar de novo/perder no meio, ver [PersistentTableStore.update]): gravar o
     * instante ANTES de [persistence] confirmar criaria uma conta dupla se o game-service reenviar o aviso
     * depois de um erro de rede/banco (o estado em memoria so muda se a gravacao deu certo, entao o proximo
     * [wasInGame] so fica `true` de novo se esta chamada nao tiver realmente concluido a transicao).
     */
    private fun recordMatchEndedIfJustNow(wasInGame: Boolean, view: TableView) {
        if (wasInGame && view.state == TableState.ENCERRADA) matchEndedTimestamps.addLast(clock())
    }

    /** §18.7: numeros do Painel do console de gerenciamento. [onlinePlayers] vem de fora (depende do [GuestRegistry]). */
    fun stats(onlinePlayers: Int): AdminStatsDto = synchronized(lock) {
        val all = store.all()
        AdminStatsDto(
            playersOnline = onlinePlayers,
            tablesOpen = all.count { it.state == TableState.ABERTA },
            tablesInGame = all.count { it.state == TableState.EM_JOGO },
            matchesEndedLastHour = matchesEndedLastHour(),
        )
    }

    private fun matchesEndedLastHour(): Int {
        val cutoff = clock() - 3_600_000L
        while (true) {
            val head = matchEndedTimestamps.peekFirst() ?: break
            if (head < cutoff) matchEndedTimestamps.pollFirst() else break
        }
        return matchEndedTimestamps.size
    }

    /**
     * §18.7: mesas onde [accountId] esta sentado AGORA (nao e historico: uma mesa ENCERRADA some do [store] poucos
     * minutos depois, ver [LobbyConfig.closedRetentionMs]). So leitura: nunca chama [maintainLocked] (que pode
     * abrir/fechar mesas) para uma consulta do console nao ter efeito colateral nenhum.
     */
    fun currentTablesOf(accountId: String): List<AdminTableSummary> = synchronized(lock) {
        store.all().filter { it.seatOf(accountId) >= 0 }.map { AdminTableSummary(it.id, it.name, it.state, it.lastActivityMs) }
    }

    /**
     * §18.7: mesas para o console de gerenciamento, em QUALQUER estado (ao contrario de [list], que nunca mostra
     * ENCERRADA — aqui o operador pode ver uma mesa recem-encerrada para investigar). So leitura: nunca chama
     * [maintainLocked] (uma consulta do console nao pode abrir nem fechar mesa). Mais recente primeiro dentro de
     * cada estado; ABERTA e EM_JOGO antes de ENCERRADA. @return (total sem paginar, a pagina pedida).
     */
    fun adminTables(limit: Int, offset: Int): Pair<Int, List<TableView>> = synchronized(lock) {
        val all = store.all().sortedWith(
            compareBy<Table> { stateOrder(it.state) }.thenByDescending { it.lastActivityMs }.thenBy { it.id },
        )
        val page = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, ADMIN_TABLES_MAX_LIMIT)).map { it.toView() }
        all.size to page
    }

    private fun stateOrder(state: TableState) = when (state) {
        TableState.ABERTA -> 0
        TableState.EM_JOGO -> 1
        TableState.ENCERRADA -> 2
    }

    /** §18.7: a mesa, em qualquer estado (404 tipado se nao existe). So leitura. */
    fun adminTable(rawId: String): TableView = synchronized(lock) {
        (store.find(idOf(rawId)) ?: missing()).toView()
    }

    /**
     * §18.7 "Encerrar mesa travada": o operador encerra a mesa, idempotente (ENCERRADA fica como esta), em
     * qualquer estado. Mesa EM_JOGO: o CHAMADOR ja avisou o game-service ANTES de chamar isto
     * ([GameGateway.closeByOperator], §18.4 decisao de 03/11/2026 - ninguem e punido, a aposta de todos os
     * humanos volta); aqui so se reflete o estado no lobby, exatamente como o DELETE do anfitriao faz com
     * [close] depois de [GameGateway.closeMatch]. Conta para [recordMatchEndedIfJustNow] como as demais.
     */
    fun adminClose(rawId: String): TableView {
        var wasInGame = false
        val view = mutate(rawId) { t ->
            if (t.state == TableState.EM_JOGO) wasInGame = true
            if (t.state == TableState.ENCERRADA) t else t.copy(state = TableState.ENCERRADA, starting = false)
        }
        recordMatchEndedIfJustNow(wasInGame, view)
        return view
    }

    fun close(guest: Guest, rawId: String): TableView {
        var wasInGame = false
        val view = mutate(rawId) { t ->
            requireHost(t, guest)
            if (t.state == TableState.ENCERRADA) return@mutate t
            requireCanClose(t)
            wasInGame = true // so chega aqui com EM_JOGO (ABERTA cai em requireCanClose, ENCERRADA no return@mutate acima)
            t.copy(state = TableState.ENCERRADA)
        }
        recordMatchEndedIfJustNow(wasInGame, view)
        return view
    }

    // ------------------------------------------------------------------------------------------ conjunto de mesas

    private fun maintainLocked() {
        sweep()
        val now = clock()
        val open = store.all().filter { it.state == TableState.ABERTA }
        val withRoom = open.any { !it.starting && it.seats.any { s -> s is Seat.Vago } }
        if (!withRoom) {
            // §18.2: lotou sem outra com lugar: a contagem em curso termina e a mesa abre ja (nunca duas).
            nextOpenAt = null
            openServerTable(now)
            return
        }
        val due = nextOpenAt ?: return
        if (now >= due) {
            nextOpenAt = null
            if (open.none { it.humans() == 0 }) openServerTable(now) // nunca mais de uma aberta vazia
        }
    }

    private fun openServerTable(now: Long) {
        val seats = List(config.serverRules.mode.seats) { Seat.Vago }
        val name = friendlyName()
        repeat(MAX_ID_ATTEMPTS) {
            val table = Table(TableId.generate(random), null, config.serverRules, seats, TableState.ABERTA, now, createdMs = now, name = name)
            if (store.insert(table)) return
        }
        error("nao foi possivel gerar o id da mesa")
    }

    /** §18.2: nome sorteado entre os que nenhuma mesa existente usa; esgotados, o nome ganha um número. */
    private fun friendlyName(): String {
        val used = store.all().map { it.name }.toSet()
        val free = TABLE_NAMES.map { "Mesa $it" }.filter { it !in used }
        if (free.isNotEmpty()) return free.random(random)
        var n = 2
        while (true) {
            val candidate = "Mesa ${TABLE_NAMES.random(random)} $n"
            if (candidate !in used) return candidate
            n++
        }
    }

    private fun sweep() {
        val now = clock()
        store.removeIf { t ->
            val idle = now - t.lastActivityMs
            when (t.state) {
                TableState.ABERTA -> false
                TableState.ENCERRADA -> idle > config.closedRetentionMs
                TableState.EM_JOGO -> idle > config.playingTtlMs
            }
        }
        // Sala abandonada (ninguem consulta ha muito tempo) volta a ser mesa aberta vazia; sobra no maximo uma vazia.
        store.all().filter { it.state == TableState.ABERTA && !it.starting && it.humans() > 0 && now - it.lastActivityMs > config.openTtlMs }
            .forEach { stale -> store.update(stale.id) { it.copy(seats = it.seats.map { Seat.Vago }, host = null, entryOrder = emptyList()) } }
        val empties = store.all().filter { it.state == TableState.ABERTA && it.humans() == 0 }.sortedBy { it.createdMs }
        if (empties.size > 1) {
            val extra = empties.drop(1).map { it.id }.toSet()
            store.removeIf { it.id in extra }
        }
    }

    private fun mutate(rawId: String, f: (Table) -> Table): TableView = synchronized(lock) {
        maintainLocked()
        val table = store.update(idOf(rawId)) { t ->
            val next = f(t)
            if (next === t) t else next.touched(clock())
        } ?: missing()
        maintainLocked()
        (store.find(table.id) ?: table).toView()
    }

    // ------------------------------------------------------------------------------------------ apoio

    private fun Table.humans() = seats.count { it is Seat.Humano }

    private fun Table.humanIds() = seats.filterIsInstance<Seat.Humano>().map { it.guestId }.toSet()

    /** Antes do jogo todo sentado conta como conectado (a sala consulta o lobby); em jogo vale o que o game-service diz. */
    private fun Table.isConnected(guestId: String) = state != TableState.EM_JOGO || guestId in connected

    /** Libera o lugar antes do jogo; se era o anfitriao, o cargo passa ao proximo pela ordem de entrada. */
    private fun Table.vacate(idx: Int): Table {
        val gone = (seats[idx] as Seat.Humano).guestId
        return withSeat(idx, Seat.Vago).copy(entryOrder = entryOrder - gone).reelectHost()
    }

    private fun Table.reelectHost(): Table {
        val current = host
        if (current != null && seatOf(current.id) >= 0 && current.id !in abandoned && isConnected(current.id)) return this
        val next = entryOrder.firstOrNull { id -> seatOf(id) >= 0 && id !in abandoned && isConnected(id) }
        val seat = next?.let { seats[seatOf(it)] as Seat.Humano }
        return copy(host = seat?.let { Guest(it.guestId, it.name) })
    }

    private fun idOf(raw: String): String = TableId.normalize(raw) ?: missing()

    private fun missing(): Nothing = lobbyError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada.", 404)

    private fun requireHost(t: Table, g: Guest) {
        if (t.host?.id != g.id) lobbyError(ErrorCode.SEM_PERMISSAO, "Só o anfitrião pode fazer isso.", 403)
    }

    private fun requireCanClose(t: Table) {
        if (t.starting) lobbyError(ErrorCode.MESA_JA_INICIADA, "O jogo desta mesa está começando.", 409)
        if (t.state == TableState.ABERTA) {
            lobbyError(ErrorCode.SEM_PERMISSAO, "A mesa do servidor não pode ser encerrada antes do jogo.", 403)
        }
    }

    private fun requireOpen(t: Table) {
        if (t.starting) lobbyError(ErrorCode.MESA_JA_INICIADA, "O jogo desta mesa está começando.", 409)
        when (t.state) {
            TableState.ABERTA -> Unit
            TableState.EM_JOGO -> lobbyError(ErrorCode.MESA_JA_INICIADA, "O jogo desta mesa já começou.", 409)
            TableState.ENCERRADA -> lobbyError(ErrorCode.MESA_INEXISTENTE, "Esta mesa foi encerrada.", 404)
        }
    }

    private fun checkSeat(t: Table, seat: Int) {
        if (seat !in t.seats.indices) lobbyError(ErrorCode.LUGAR_INVALIDO, "Lugar inexistente nesta mesa.", 400)
    }

    private fun Table.withSeat(i: Int, s: Seat) = copy(seats = seats.toMutableList().also { it[i] = s })

    companion object {
        private const val MAX_ID_ATTEMPTS = 50

        /** §18.7: paginacao de GET /internal/admin/tables (mesmo teto de GET /internal/admin/accounts). */
        const val ADMIN_TABLES_MAX_LIMIT = 100

        /** Praias do Brasil, com as mais conhecidas de Florianópolis (§18.2). */
        val TABLE_NAMES = listOf(
            // Florianópolis
            "Joaquina", "Campeche", "Jurerê", "Mole", "Ingleses", "Canasvieiras", "Barra da Lagoa", "Lagoinha do Leste",
            "Santinho", "Brava", "Armação", "Daniela", "Moçambique", "Pântano do Sul", "Matadeiro", "Galheta",
            // outras praias do Brasil
            "Ipanema", "Copacabana", "Jericoacoara", "Porto de Galinhas", "Trancoso", "Maragogi", "Pipa", "Canoa Quebrada",
            "Itacaré", "Caraíva", "Garopaba", "Geribá", "Lopes Mendes", "Praia do Forte",
        )
    }
}

fun Table.toView(photoVersion: (String) -> Int? = { null }) = TableView(
    id = id,
    name = name,
    hostId = host?.id.orEmpty(),
    hostName = host?.name.orEmpty(),
    state = state,
    rules = rules,
    seats = seats.mapIndexed { i, s ->
        when (s) {
            Seat.Vago -> SeatView(i, SeatKind.VAGO)
            is Seat.Robo -> SeatView(i, SeatKind.ROBO, profile = s.profile, level = s.level)
            is Seat.Humano -> SeatView(
                i, SeatKind.HUMANO, s.guestId, s.name,
                photoUrl = photoVersion(s.guestId)?.let { v -> "/api/lobby/guests/${s.guestId}/photo?v=$v" },
            )
        }
    },
)
