package com.gtranca.online.lobby

import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LobbyServiceTest {
    private var now = 1_000_000L
    private val store = InMemoryTableStore()
    private val service = LobbyService(store, { now }, Random(1))
    private val ana = Guest("a", "Ana")
    private val beto = Guest("b", "Beto")
    private val caio = Guest("c", "Caio")
    private val dani = Guest("d", "Dani")
    private val edu = Guest("e", "Edu")

    private fun codeOf(block: () -> Unit): ErrorCode = shouldThrow<LobbyException> { block() }.code
    private fun tables() = service.list(0).tables
    private fun open() = tables().filter { it.state == TableState.ABERTA }
    private fun firstOpen(): TableView = open().first()
    private fun minutes(m: Int) { now += m * 60_000L }

    // ------------------------------------------------------------------------------------------ mesas do servidor

    @Test
    fun `18_2 o servidor sempre tem uma mesa aberta com as opcoes fixas`() {
        val t = tables().single()
        t.state shouldBe TableState.ABERTA
        TableId.normalize(t.id) shouldBe t.id
        t.hostId shouldBe ""
        t.seats.map { it.kind } shouldBe List(4) { SeatKind.VAGO }
        t.rules shouldBe TableRules(
            mode = TableMode.DUPLAS, rulesJson = "{}", targetScore = 1500, bet = 50, skillBoostersAllowed = true,
            tempoDeJogada = TempoDeJogada.QUINZE_MINUTOS, punicao = Punicao.PERDE_APOSTA, robotLevel = BotLevel.FACIL,
        )
    }

    @Test
    fun `18_2 o jogador nao cria mesa nesta fase`() {
        codeOf { service.createTable(ana, SERVER_TABLE_RULES) } shouldBe ErrorCode.SEM_PERMISSAO
    }

    @Test
    fun `18_2 cinco minutos depois do primeiro jogador o servidor abre outra mesa`() {
        service.join(ana, firstOpen().id, null)
        minutes(4)
        open().size shouldBe 1
        now += 59_999
        open().size shouldBe 1
        now += 1
        open().size shouldBe 2
        minutes(6)
        open().size shouldBe 2 // a contagem nao se repete sozinha
    }

    @Test
    fun `18_2 lotar sem outra mesa com lugar antecipa a abertura e nao abre duas`() {
        val id = firstOpen().id
        listOf(ana, beto, caio, dani).forEach { service.join(it, id, null) }
        open().size shouldBe 2 // abriu na hora
        minutes(5)
        open().size shouldBe 2 // a contagem foi encerrada: nao abre outra
    }

    @Test
    fun `18_2 o servidor nunca guarda mais de uma mesa aberta vazia`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        minutes(5)
        open().size shouldBe 2
        service.leave(ana, id) // agora as duas estao vazias
        open().size shouldBe 1
    }

    @Test
    fun `18_2 iniciar o jogo deixa a mesa em jogo na lista e abre outra se nao sobrar lugar`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        service.beginStart(ana, id)
        service.commitStart(id)
        val list = tables()
        list.map { it.state } shouldBe listOf(TableState.ABERTA, TableState.EM_JOGO) // abertas primeiro
        list.last().id shouldBe id
    }

    @Test
    fun `18_2 mesa encerrada sai da lista`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        service.beginStart(ana, id)
        service.commitStart(id)
        service.matchEnded(id)
        tables().none { it.id == id } shouldBe true
    }

    @Test
    fun `18_2 a lista informa os jogadores online`() {
        service.list(7).onlinePlayers shouldBe 7
    }

    // ------------------------------------------------------------------------------------------ entrar e sair

    @Test
    fun `18_2 o primeiro a entrar e o anfitriao e entrar de novo e idempotente`() {
        val id = firstOpen().id
        service.join(ana, id, null).hostId shouldBe "a"
        service.join(beto, id, null).hostId shouldBe "a"
        service.join(beto, id, null).seats.count { it.kind == SeatKind.HUMANO } shouldBe 2
    }

    @Test
    fun `18_2 lugar escolhido ocupado e mesa cheia recusam`() {
        val id = firstOpen().id
        service.join(ana, id, 2)
        codeOf { service.join(beto, id, 2) } shouldBe ErrorCode.MESA_CHEIA
        listOf(beto, caio, dani).forEach { service.join(it, id, null) }
        codeOf { service.join(edu, id, null) } shouldBe ErrorCode.MESA_CHEIA
    }

    @Test
    fun `18_2 o jogador nao fica em duas mesas abertas ao mesmo tempo`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        minutes(5)
        val other = open().first { it.id != id }.id
        codeOf { service.join(ana, other, null) } shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_2 o anfitriao que sai passa o cargo ao proximo pela ordem de entrada`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        service.join(beto, id, null)
        service.join(caio, id, null)
        service.leave(ana, id).hostId shouldBe "b"
        service.leave(beto, id).hostId shouldBe "c"
        service.leave(caio, id).hostId shouldBe ""
    }

    @Test
    fun `18_2 so o anfitriao remove um humano e nao remove a si mesmo`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        val b = service.join(beto, id, null).seats.first { it.guestId == "b" }.index
        codeOf { service.removeHuman(beto, id, 0) } shouldBe ErrorCode.SEM_PERMISSAO
        codeOf { service.removeHuman(ana, id, 0) } shouldBe ErrorCode.LUGAR_INVALIDO
        service.removeHuman(ana, id, b).seats[b].kind shouldBe SeatKind.VAGO
    }

    // ------------------------------------------------------------------------------------------ Entregar a mesa

    @Test
    fun `18_2 entregar a mesa passa o cargo a outro humano e so o anfitriao entrega`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        val b = service.join(beto, id, null).seats.first { it.guestId == "b" }.index
        codeOf { service.passHost(beto, id, 0) } shouldBe ErrorCode.SEM_PERMISSAO
        codeOf { service.passHost(ana, id, 0) } shouldBe ErrorCode.LUGAR_INVALIDO // a si mesmo
        service.passHost(ana, id, b).hostId shouldBe "b"
        codeOf { service.passHost(ana, id, 0) } shouldBe ErrorCode.SEM_PERMISSAO // ja nao e anfitriao
    }

    @Test
    fun `18_2 em jogo o anfitriao so entrega a um humano conectado`() {
        val id = firstOpen().id
        service.join(ana, id, 0)
        service.join(beto, id, 1)
        service.beginStart(ana, id)
        service.commitStart(id)
        service.presence(id, connected = setOf("a"), abandoned = emptySet())
        codeOf { service.passHost(ana, id, 1) } shouldBe ErrorCode.LUGAR_INVALIDO
        service.presence(id, connected = setOf("a", "b"), abandoned = emptySet())
        service.passHost(ana, id, 1).hostId shouldBe "b"
    }

    @Test
    fun `18_2 anfitriao que cai em jogo passa o cargo ao proximo conectado e nunca a um robo`() {
        val id = firstOpen().id
        service.join(ana, id, 0)
        service.join(beto, id, 1)
        service.join(caio, id, 2)
        service.beginStart(ana, id)
        service.commitStart(id)
        service.presence(id, connected = setOf("b", "c"), abandoned = emptySet()).hostId shouldBe "b"
        service.presence(id, connected = setOf("a", "c"), abandoned = emptySet()).hostId shouldBe "a" // Beto caiu: 1a da ordem
        service.presence(id, connected = setOf("c"), abandoned = emptySet()).hostId shouldBe "c" // Ana caiu
        service.presence(id, connected = setOf("a"), abandoned = setOf("c")).hostId shouldBe "a" // Caio desistiu
        service.presence(id, connected = emptySet(), abandoned = emptySet()).hostId shouldBe "" // ninguem pode assumir
        service.presence(id, connected = setOf("a"), abandoned = emptySet()).hostId shouldBe "a" // volta a ter quem assuma
    }

    // ------------------------------------------------------------------------------------------ iniciar

    @Test
    fun `18_2 o anfitriao inicia quando quiser e os lugares vagos viram robos faceis com personagens diferentes`() {
        val id = firstOpen().id
        service.join(ana, id, 0)
        service.join(beto, id, null)
        codeOf { service.beginStart(beto, id) } shouldBe ErrorCode.SEM_PERMISSAO
        val plan = service.beginStart(ana, id)
        plan.rules shouldBe SERVER_TABLE_RULES
        val t = service.commitStart(id)
        t.state shouldBe TableState.EM_JOGO
        val robots = t.seats.filter { it.kind == SeatKind.ROBO }
        robots.size shouldBe 2
        robots.all { it.level == BotLevel.FACIL } shouldBe true
        robots.map { it.profile }.toSet().size shouldBe 2
    }

    @Test
    fun `18_3 se a partida nao e criada os robos saem e a mesa segue aberta`() {
        val id = firstOpen().id
        service.join(ana, id, 0)
        service.beginStart(ana, id)
        val t = service.abortStart(id)
        t.state shouldBe TableState.ABERTA
        t.seats.count { it.kind == SeatKind.VAGO } shouldBe 3
    }

    @Test
    fun `18_2 mesa em jogo nao aceita mais ninguem e nao se encerra antes do jogo`() {
        val id = firstOpen().id
        service.join(ana, id, 0)
        codeOf { service.close(ana, id) } shouldBe ErrorCode.SEM_PERMISSAO
        service.beginStart(ana, id)
        service.commitStart(id)
        codeOf { service.join(beto, id, null) } shouldBe ErrorCode.MESA_JA_INICIADA
        codeOf { service.beginStart(ana, id) } shouldBe ErrorCode.MESA_JA_INICIADA
        service.close(ana, id).state shouldBe TableState.ENCERRADA
    }

    @Test
    fun `18_2 sala sem atividade volta a ser mesa aberta vazia e consulta de quem esta sentado renova`() {
        val id = firstOpen().id
        service.join(ana, id, null)
        minutes(20)
        service.get(ana, id)
        minutes(20)
        service.get(beto, id).seats.count { it.kind == SeatKind.HUMANO } shouldBe 1
        minutes(31)
        val t = tables().first { it.id == id }
        t.seats.all { it.kind == SeatKind.VAGO } shouldBe true
        t.hostId shouldBe ""
    }

    @Test
    fun `18_2 entradas concorrentes nunca passam do numero de lugares`() {
        val id = firstOpen().id
        val threads = (1..20).map { i ->
            Thread { runCatching { service.join(Guest("g$i", "G$i"), id, null) } }.also { it.start() }
        }
        threads.forEach { it.join() }
        tables().first { it.id == id }.seats.count { it.kind == SeatKind.HUMANO } shouldBe 4
    }

    @Test
    fun `18_2 id sem diferenca entre maiusculas e minusculas`() {
        val id = firstOpen().id
        service.join(ana, id.lowercase(), null).id shouldBe id
    }
}
