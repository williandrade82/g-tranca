package com.gtranca.ui.online

import androidx.lifecycle.viewModelScope
import com.gtranca.data.OnlineGuest
import com.gtranca.data.Settings
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TempoDeJogada
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Telas online (docs/telas.md 3.21 e 3.23): conta de convidado, lista de mesas do servidor e a sala (§18.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val lobby = FakeLobby()
    private val settings = FakeSettings(Settings(onlineServer = "servidor:8080"))
    private fun account(name: String = "Ana") = OnlineAccount(lobby, settings) { name }

    // ---------- conta de convidado ----------

    // §18.1 convidado provisório com o nome do Perfil, guardado só no aparelho
    @Test
    fun `cria o convidado com o nome do Perfil e guarda`() = runTest(dispatcher) {
        val g = account("Ana").guest()
        g.name shouldBe "Ana"
        settings.state.value.onlineGuest shouldBe g
        account("Outro").guest() shouldBe g // reaproveita o guardado
        lobby.calls.count { it == "createGuest" } shouldBe 1
    }

    // §18.1 o nome do convidado respeita o limite do servidor (24)
    @Test
    fun `corta o nome em 24 caracteres`() = runTest(dispatcher) {
        account("A".repeat(40)).guest().name.length shouldBe 24
    }

    // §18.1 lobby reiniciado (convidados em memória): recria o convidado e repete a chamada uma vez
    @Test
    fun `token esquecido pelo servidor recria o convidado e repete a chamada`() = runTest(dispatcher) {
        val acc = account()
        val first = acc.guest()
        lobby.forgotten += first.token
        val id = lobby.serverTable()
        val table = acc.call { api, g -> api.join(g.token, id) }
        table.hostId shouldBe settings.state.value.onlineGuest!!.id
        (settings.state.value.onlineGuest!!.token == first.token) shouldBe false
    }

    // ---------- 3.21 Jogar online ----------

    // §18.1 sem servidor informado o online mostra o que falta
    @Test
    fun `sem servidor no Perfil mostra NO_SERVER`() = runTest(dispatcher) {
        val vm = OnlineHomeViewModel(FakeSettings()) { null }
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineHomeStatus.NO_SERVER
    }

    // §18.1 com servidor, o convidado e criado e a tela libera criar e entrar
    @Test
    fun `com servidor entra como convidado e fica READY`() = runTest(dispatcher) {
        val vm = OnlineHomeViewModel(settings) { account("Ana") }
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.uiState.value.name shouldBe "Ana"
        vm.viewModelScope.cancel() // a lista de mesas consulta sem parar
    }

    // §18.8 versão incompatível e rede viram mensagem com Tentar de novo
    @Test
    fun `falha de versao ou rede vira ERROR e o retry recupera`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.UPDATE_APP, ErrorCode.VERSAO_INCOMPATIVEL, 426)
        val vm = OnlineHomeViewModel(settings) { account() }
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.ERROR
        vm.uiState.value.problem shouldBe OnlineProblem.UPDATE_APP
        vm.retry()
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.viewModelScope.cancel()
    }

    // §18.8 servidor em manutenção
    @Test
    fun `503 vira MAINTENANCE`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.MAINTENANCE, ErrorCode.VERSAO_INCOMPATIVEL, 503)
        val vm = OnlineHomeViewModel(settings) { account() }
        runCurrent()
        vm.uiState.value.problem shouldBe OnlineProblem.MAINTENANCE
        vm.viewModelScope.cancel()
    }

    // ---------- 3.21 lista de mesas do servidor (§18.2) ----------

    private fun home() = OnlineHomeViewModel(settings, pollMs = 3_000) { account("Ana") }

    // §18.2 a tela mostra os jogadores online e as mesas do servidor, abertas primeiro
    @Test
    fun `a lista mostra os jogadores online e as mesas abertas antes das em jogo`() = runTest(dispatcher) {
        val playing = lobby.serverTable()
        lobby.otherJoins(playing, "Beto")
        lobby.setState(playing, TableState.EM_JOGO)
        val open = lobby.serverTable()
        lobby.onlinePlayers = 5
        val vm = home()
        runCurrent()
        vm.uiState.value.onlinePlayers shouldBe 5
        vm.uiState.value.tables!!.map { it.id } shouldBe listOf(open, playing)
        vm.viewModelScope.cancel()
    }

    // §18.2 a lista se atualiza sozinha
    @Test
    fun `a lista atualiza sozinha`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        vm.uiState.value.tables!!.single().seats.count { it.kind == SeatKind.HUMANO } shouldBe 0
        lobby.otherJoins(id, "Beto")
        advanceTimeBy(3_001)
        runCurrent()
        vm.uiState.value.tables!!.single().seats.count { it.kind == SeatKind.HUMANO } shouldBe 1
        vm.viewModelScope.cancel()
    }

    // §18.2 tocar numa mesa mostra as opcoes e so entra ao ocupar o lugar; o 1o a entrar e o anfitriao
    @Test
    fun `tocar numa mesa mostra as opcoes e ocupar entra como anfitriao`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        vm.onSelect(vm.uiState.value.tables!!.single())
        vm.uiState.value.selected!!.id shouldBe id
        lobby.calls.count { it == "join" } shouldBe 0
        vm.onOccupy()
        runCurrent()
        vm.uiState.value.joinedId shouldBe id
        lobby.tables.getValue(id).hostId shouldBe settings.state.value.onlineGuest!!.id
        vm.viewModelScope.cancel()
    }

    // §18.2 mesa em jogo e so para ver
    @Test
    fun `mesa em jogo nao abre`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        lobby.otherJoins(id, "Beto")
        lobby.setState(id, TableState.EM_JOGO)
        val vm = home()
        runCurrent()
        vm.onSelect(vm.uiState.value.tables!!.single())
        vm.uiState.value.selected shouldBe null
        vm.viewModelScope.cancel()
    }

    // §18.2 quem ja esta sentado volta direto a sala
    @Test
    fun `a minha mesa volta direto a sala`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        account("Ana").call { api, g -> api.join(g.token, id) }
        val vm = home()
        runCurrent()
        vm.uiState.value.myTable!!.id shouldBe id
        vm.onSelect(vm.uiState.value.tables!!.single())
        vm.uiState.value.joinedId shouldBe id
        vm.viewModelScope.cancel()
    }

    // §18.2 recusa ao entrar (mesa lotou nesse meio tempo) vira mensagem
    @Test
    fun `mesa que lotou antes de entrar mostra o problema`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        vm.onSelect(vm.uiState.value.tables!!.single())
        repeat(4) { lobby.otherJoins(id, "J$it") }
        vm.onOccupy()
        runCurrent()
        vm.uiState.value.problem shouldBe OnlineProblem.TABLE_FULL
        vm.uiState.value.joinedId shouldBe null
        vm.viewModelScope.cancel()
    }

    // ---------- 3.23 sala ----------

    private fun room(id: String, acc: OnlineAccount = account("Ana")) = OnlineRoomViewModel(acc, id)

    /** Mesa do servidor com Ana sentada (anfitria, a 1a a entrar). */
    private suspend fun newTable(): String = lobby.serverTable().also { id -> account("Ana").call { api, g -> api.join(g.token, id) } }

    // §18.2 quem entra aparece sozinho (consulta a cada ~2 s)
    @Test
    fun `a lista de lugares atualiza ao vivo`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.uiState.value.isHost shouldBe true
        vm.uiState.value.table!!.seats[1].kind shouldBe SeatKind.VAGO
        lobby.otherJoins(id, "Beto")
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.table!!.seats[1].name shouldBe "Beto"
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitriao inicia quando quiser; os lugares livres viram robos
    @Test
    fun `anfitriao inicia com lugares livres e eles viram robos`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.uiState.value.canStart shouldBe true
        vm.onStart()
        runCurrent()
        vm.uiState.value.started shouldBe true
        vm.uiState.value.table!!.seats.count { it.kind == SeatKind.ROBO } shouldBe 3
        vm.viewModelScope.cancel()
    }

    // §18.2 Entregar a mesa: escolhe outro humano da sala
    @Test
    fun `anfitriao entrega a mesa a outro jogador`() = runTest(dispatcher) {
        val id = newTable()
        val beto = lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.uiState.value.passCandidates shouldBe listOf(1)
        vm.onAskPass()
        vm.uiState.value.choosingHost shouldBe true
        vm.onPassTo(1)
        runCurrent()
        vm.uiState.value.choosingHost shouldBe false
        vm.uiState.value.isHost shouldBe false
        lobby.tables.getValue(id).hostId shouldBe beto.id
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitriao tambem sai; o cargo passa ao proximo
    @Test
    fun `anfitriao sai e o cargo passa ao proximo`() = runTest(dispatcher) {
        val id = newTable()
        val beto = lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.onLeave()
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.LEFT
        lobby.tables.getValue(id).hostId shouldBe beto.id
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitrião remove um humano
    @Test
    fun `anfitriao remove um jogador`() = runTest(dispatcher) {
        val id = newTable()
        lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.onRemove(1)
        runCurrent()
        vm.uiState.value.table!!.seats[1].kind shouldBe SeatKind.VAGO
        vm.viewModelScope.cancel()
    }

    // §18.2 quem não é anfitrião vê a sala só para leitura e pode sair
    @Test
    fun `convidado entra, ve a sala sem poderes de anfitriao e sai`() = runTest(dispatcher) {
        val id = newTable()
        val betoSettings = FakeSettings(Settings(onlineServer = "x"))
        val beto = OnlineAccount(lobby, betoSettings) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        vm.uiState.value.isHost shouldBe false
        vm.uiState.value.canStart shouldBe false
        vm.uiState.value.passCandidates shouldBe listOf(0)
        vm.uiState.value.mySeat shouldBe 1
        vm.onLeave()
        runCurrent()
        lobby.tables.getValue(id).seats[1].kind shouldBe SeatKind.VAGO
        vm.uiState.value.ended shouldBe OnlineRoomEnd.LEFT
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitrião encerra: quem está na sala é avisado
    @Test
    fun `mesa encerrada pelo anfitriao avisa quem esta na sala`() = runTest(dispatcher) {
        val id = newTable()
        val beto = OnlineAccount(lobby, FakeSettings(Settings(onlineServer = "x"))) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        lobby.setState(id, TableState.ENCERRADA)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.CLOSED
        vm.viewModelScope.cancel()
    }

    // §18.2 removido pelo anfitrião
    @Test
    fun `jogador removido sai da sala`() = runTest(dispatcher) {
        val id = newTable()
        val beto = OnlineAccount(lobby, FakeSettings(Settings(onlineServer = "x"))) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        account("Ana").call { api, g -> api.removeHuman(g.token, id, 1) }
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.REMOVED
        vm.viewModelScope.cancel()
    }

    @Test
    fun `mesa que sumiu do servidor termina a sala`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        lobby.tables.remove(id)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.NOT_FOUND
        vm.viewModelScope.cancel()
    }

    // §18.4 queda de rede: a sala segue tentando e mostra o aviso
    @Test
    fun `queda de rede mantem a sala e tenta de novo`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        lobby.failAll = OnlineException(OnlineErrorKind.NETWORK)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.connectionLost shouldBe true
        vm.uiState.value.table!!.id shouldBe id
        lobby.failAll = null
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.connectionLost shouldBe false
        vm.viewModelScope.cancel()
    }

    // ---------- erros em pt-BR ----------

    // §18.8 cada falha do cliente tem um problema com texto próprio
    @Test
    fun `traduz as falhas do cliente`() {
        OnlineException(OnlineErrorKind.UPDATE_APP, status = 426).toProblem() shouldBe OnlineProblem.UPDATE_APP
        OnlineException(OnlineErrorKind.MAINTENANCE, status = 503).toProblem() shouldBe OnlineProblem.MAINTENANCE
        OnlineException(OnlineErrorKind.NETWORK).toProblem() shouldBe OnlineProblem.NETWORK
        OnlineException(OnlineErrorKind.API, ErrorCode.MESA_CHEIA).toProblem() shouldBe OnlineProblem.TABLE_FULL
        OnlineException(OnlineErrorKind.API, ErrorCode.MESA_JA_INICIADA).toProblem() shouldBe OnlineProblem.ALREADY_STARTED
        OnlineException(OnlineErrorKind.API, ErrorCode.SEM_PERMISSAO).toProblem() shouldBe OnlineProblem.NOT_HOST
        OnlineException(OnlineErrorKind.API, ErrorCode.LUGARES_VAGOS).toProblem() shouldBe OnlineProblem.EMPTY_SEATS
        OnlineException(OnlineErrorKind.API, ErrorCode.PARTIDA_INDISPONIVEL).toProblem() shouldBe OnlineProblem.GAME_UNAVAILABLE
        OnlineException(OnlineErrorKind.API, ErrorCode.SALDO_INSUFICIENTE).toProblem() shouldBe OnlineProblem.INSUFFICIENT_BALANCE
        OnlineException(OnlineErrorKind.API, ErrorCode.APP_NAO_RECONHECIDO).toProblem() shouldBe OnlineProblem.APP_NOT_RECOGNIZED
    }

    @Test
    fun `o token do convidado nao aparece no toString`() {
        OnlineGuest("g1", "Ana", "segredo").toString().contains("segredo") shouldBe false
    }
}
