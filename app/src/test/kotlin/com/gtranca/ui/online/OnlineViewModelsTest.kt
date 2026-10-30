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

/** Telas online da etapa A (docs/telas.md 3.21 a 3.24): conta de convidado, criar, entrar e a sala. */
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
        val table = acc.call { api, g -> api.createTable(g.token, FakeLobby.rules) }
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
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.uiState.value.name shouldBe "Ana"
    }

    // §18.8 versão incompatível e rede viram mensagem com Tentar de novo
    @Test
    fun `falha de versao ou rede vira ERROR e o retry recupera`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.UPDATE_APP, ErrorCode.VERSAO_INCOMPATIVEL, 426)
        val vm = OnlineHomeViewModel(settings) { account() }
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineHomeStatus.ERROR
        vm.uiState.value.problem shouldBe OnlineProblem.UPDATE_APP
        vm.retry()
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
    }

    // §18.8 servidor em manutenção
    @Test
    fun `503 vira MAINTENANCE`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.MAINTENANCE, ErrorCode.VERSAO_INCOMPATIVEL, 503)
        val vm = OnlineHomeViewModel(settings) { account() }
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.MAINTENANCE
    }

    // ---------- 3.23 Criar mesa ----------

    // §18.4 critérios padrão da mesa: indeterminado, sem punição, robôs Fáceis; aposta fixa em 0 (§18.5)
    @Test
    fun `cria a mesa com os criterios escolhidos e aposta zero`() = runTest(dispatcher) {
        val vm = OnlineCreateViewModel(account(), settings)
        advanceUntilIdle()
        vm.uiState.value.tempo shouldBe TempoDeJogada.INDETERMINADO
        vm.uiState.value.punicao shouldBe Punicao.SEM_PUNICAO
        vm.uiState.value.robotLevel shouldBe BotLevel.FACIL
        vm.uiState.value.targetText shouldBe "3000"
        vm.onMode(GameMode.DUPLAS)
        vm.onRules(OnlineRulesChoice.PAULISTA)
        vm.onTarget("1500")
        vm.onSkillBoosters(false)
        vm.onTempo(TempoDeJogada.UM_MINUTO)
        vm.onPunicao(Punicao.PERDE_APOSTA)
        vm.onRobotLevel(BotLevel.DIFICIL)
        vm.onCreate()
        advanceUntilIdle()
        val id = vm.uiState.value.createdId!!
        val rules = lobby.tables.getValue(id).rules
        rules.mode shouldBe TableMode.DUPLAS
        rules.targetScore shouldBe 1500
        rules.bet shouldBe 0
        rules.skillBoostersAllowed shouldBe false
        rules.tempoDeJogada shouldBe TempoDeJogada.UM_MINUTO
        rules.punicao shouldBe Punicao.PERDE_APOSTA
        rules.robotLevel shouldBe BotLevel.DIFICIL
        Json.decodeFromString(RuleSet.serializer(), rules.rulesJson).id shouldBe RuleSet.PAULISTA.id
    }

    // §14.4 modo e pontuação-alvo partem da última montagem da mesa
    @Test
    fun `parte do modo e do alvo da ultima montagem`() = runTest(dispatcher) {
        val s = FakeSettings(Settings(tableSetup = com.gtranca.data.TableSetup(mode = GameMode.DUPLAS, target = "500")))
        val vm = OnlineCreateViewModel(account(), s)
        advanceUntilIdle()
        vm.uiState.value.mode shouldBe GameMode.DUPLAS
        vm.uiState.value.targetText shouldBe "500"
    }

    @Test
    fun `alvo invalido nao cria e nao chama o servidor`() = runTest(dispatcher) {
        val vm = OnlineCreateViewModel(account(), settings)
        vm.onTarget("")
        vm.uiState.value.canCreate shouldBe false
        vm.onCreate()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.INVALID_TARGET
        lobby.calls.none { it == "createTable" } shouldBe true
    }

    @Test
    fun `recusa do servidor ao criar vira problema na tela`() = runTest(dispatcher) {
        val vm = OnlineCreateViewModel(account(), settings)
        advanceUntilIdle()
        lobby.failNext = OnlineException(OnlineErrorKind.API, ErrorCode.REQUISICAO_INVALIDA, 400)
        vm.onCreate()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.INVALID_REQUEST
        vm.uiState.value.creating shouldBe false
        vm.uiState.value.createdId shouldBe null
    }

    // ---------- 3.24 Entrar numa mesa ----------

    // §18.2 id fora do formato é recusado no aparelho
    @Test
    fun `id invalido e recusado sem chamar o servidor`() = runTest(dispatcher) {
        val vm = OnlineJoinViewModel(account())
        vm.onId("ab0il")
        vm.onGo()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.INVALID_ID
        lobby.calls.none { it == "table" } shouldBe true
    }

    // §18.2 o campo aceita 6 caracteres, sem diferenciar maiúsculas
    @Test
    fun `o campo limpa, corta em 6 e poe em maiusculas`() = runTest(dispatcher) {
        val vm = OnlineJoinViewModel(account())
        vm.onId(" abc 234xyz")
        vm.uiState.value.idText shouldBe "ABC234"
    }

    // §18.2 id inexistente mostra a mensagem própria sem fechar a tela
    @Test
    fun `mesa inexistente vira TABLE_NOT_FOUND`() = runTest(dispatcher) {
        val vm = OnlineJoinViewModel(account())
        vm.onId("zzzzzz")
        vm.onGo()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.TABLE_NOT_FOUND
        vm.uiState.value.invite shouldBe null
    }

    // §18.2 mesa cheia e mesa iniciada
    @Test
    fun `mesa cheia ou iniciada nao mostra o convite`() = runTest(dispatcher) {
        val host = account("Ana")
        val table = host.call { api, g -> api.createTable(g.token, FakeLobby.rules) }
        lobby.otherJoins(table.id)
        val vm = OnlineJoinViewModel(OnlineAccount(lobby, FakeSettings(Settings(onlineServer = "x"))) { "Caio" })
        vm.onId(table.id)
        vm.onGo()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.TABLE_FULL
        lobby.setState(table.id, TableState.EM_JOGO)
        vm.onGo()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineProblem.ALREADY_STARTED
    }

    // §18.4 ocupar o lugar é aceitar as regras: só entra depois do convite
    @Test
    fun `mostra o convite e so entra ao ocupar o lugar`() = runTest(dispatcher) {
        val table = account("Ana").call { api, g -> api.createTable(g.token, FakeLobby.rules) }
        val guestSettings = FakeSettings(Settings(onlineServer = "x"))
        val vm = OnlineJoinViewModel(OnlineAccount(lobby, guestSettings) { "Beto" })
        vm.onId(table.id.lowercase())
        vm.onGo()
        advanceUntilIdle()
        vm.uiState.value.invite!!.id shouldBe table.id
        lobby.tables.getValue(table.id).seats[1].kind shouldBe SeatKind.VAGO // ainda não entrou
        vm.onOccupy()
        advanceUntilIdle()
        vm.uiState.value.joinedId shouldBe table.id
        lobby.tables.getValue(table.id).seats[1].name shouldBe "Beto"
    }

    // ---------- 3.23 Mesa online (a sala) ----------

    private fun TestScope.room(table: String, acc: OnlineAccount = account("Ana"), pollMs: Long = 2_000) =
        OnlineRoomViewModel(acc, table, pollMs)

    private suspend fun newTable(): String = account("Ana").call { api, g -> api.createTable(g.token, FakeLobby.rules) }.id

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

    // §18.2 iniciar so com todos os lugares preenchidos; o anfitrião troca vago por robô
    @Test
    fun `anfitriao coloca robo e inicia quando preenchido`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.uiState.value.canStart shouldBe false
        vm.onSeatRobot(1)
        runCurrent()
        vm.uiState.value.table!!.seats[1].kind shouldBe SeatKind.ROBO
        vm.uiState.value.canStart shouldBe true
        vm.onStart()
        runCurrent()
        vm.uiState.value.started shouldBe true
        vm.viewModelScope.cancel()
    }

    @Test
    fun `iniciar com lugar vago mostra o problema e a mesa segue aberta`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.onStart()
        runCurrent()
        vm.uiState.value.problem shouldBe OnlineProblem.EMPTY_SEATS
        vm.uiState.value.started shouldBe false
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

    // §18.4 encerrar a mesa pede confirmação
    @Test
    fun `encerrar pede confirmacao e termina a sala`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.onAskClose()
        vm.uiState.value.confirmClose shouldBe true
        vm.onDismissClose()
        vm.uiState.value.confirmClose shouldBe false
        lobby.tables.getValue(id).state shouldBe TableState.ABERTA
        vm.onAskClose()
        vm.onConfirmClose()
        runCurrent()
        lobby.tables.getValue(id).state shouldBe TableState.ENCERRADA
        vm.uiState.value.ended shouldBe OnlineRoomEnd.CLOSED
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
    }

    @Test
    fun `o token do convidado nao aparece no toString`() {
        OnlineGuest("g1", "Ana", "segredo").toString().contains("segredo") shouldBe false
    }
}
