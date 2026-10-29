package com.gtranca.online.lobby

import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.Visibilidade
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
    private val rules = TableRules(TableMode.INDIVIDUAL, "{}")

    private fun codeOf(block: () -> Unit): ErrorCode = shouldThrow<LobbyException> { block() }.code

    @Test
    fun `18_2 anfitriao cria mesa com id de 6 caracteres validos e ocupa o primeiro lugar`() {
        val t = service.createTable(ana, rules)
        TableId.isValid(t.id) shouldBe true
        t.id.length shouldBe 6
        t.state shouldBe TableState.ABERTA
        t.seats.size shouldBe 2
        t.seats[0].guestId shouldBe "a"
        t.seats[1].kind shouldBe SeatKind.VAGO
    }

    @Test
    fun `18_2 modo duplas tem 4 lugares`() {
        service.createTable(ana, rules.copy(mode = TableMode.DUPLAS)).seats.size shouldBe 4
    }

    @Test
    fun `18_2 id e unico entre mesas abertas mesmo com colisoes do sorteio`() {
        // Random sempre igual: o sorteio colide sempre; a 2a mesa nao consegue id e falha de forma tratada.
        val fixed = LobbyService(InMemoryTableStore(), { now }, Random(5))
        val ids = (1..200).map { fixed.createTable(ana, rules).id }
        ids.toSet().size shouldBe 200
        val stuck = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        val s2 = LobbyService(InMemoryTableStore(), { now }, stuck)
        s2.createTable(ana, rules)
        shouldThrow<LobbyException> { s2.createTable(beto, rules) }.status shouldBe 503
    }

    @Test
    fun `18_2 id de mesa encerrada pode ser reaproveitado`() {
        val stuck = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        val s = LobbyService(InMemoryTableStore(), { now }, stuck)
        val t = s.createTable(ana, rules)
        s.close(ana, t.id)
        s.createTable(beto, rules).hostId shouldBe "b"
    }

    @Test
    fun `18_2 id sem diferenca entre maiusculas e minusculas`() {
        val t = service.createTable(ana, rules)
        service.get(beto, t.id.lowercase()).id shouldBe t.id
    }

    @Test
    fun `18_2 mesa publica ainda nao e aceita`() {
        codeOf { service.createTable(ana, rules.copy(visibilidade = Visibilidade.PUBLICA)) } shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_2 rulesJson opaco tem limite de tamanho`() {
        codeOf { service.createTable(ana, rules.copy(rulesJson = "x".repeat(8_001))) } shouldBe ErrorCode.REQUISICAO_INVALIDA
        service.createTable(ana, rules.copy(rulesJson = "x".repeat(8_000)))
    }

    @Test
    fun `18_4 criterios da mesa ficam guardados`() {
        val r = rules.copy(tempoDeJogada = TempoDeJogada.UM_MINUTO, punicao = Punicao.PERDE_APOSTA, bet = 100)
        service.createTable(ana, r).rules shouldBe r
    }

    @Test
    fun `18_2 convidado entra no primeiro lugar vago e entrar de novo e idempotente`() {
        val t = service.createTable(ana, rules.copy(mode = TableMode.DUPLAS))
        service.join(beto, t.id, null).seats[1].guestId shouldBe "b"
        service.join(beto, t.id, null).seats.count { it.kind == SeatKind.HUMANO } shouldBe 2
    }

    @Test
    fun `18_2 mesa cheia recusa e lugar escolhido ocupado recusa`() {
        val t = service.createTable(ana, rules)
        service.join(beto, t.id, null)
        codeOf { service.join(caio, t.id, null) } shouldBe ErrorCode.MESA_CHEIA
        val d = service.createTable(ana, rules.copy(mode = TableMode.DUPLAS))
        service.join(beto, d.id, 2)
        codeOf { service.join(caio, d.id, 2) } shouldBe ErrorCode.MESA_CHEIA
        codeOf { service.join(caio, d.id, 9) } shouldBe ErrorCode.LUGAR_INVALIDO
    }

    @Test
    fun `18_2 mesa inexistente`() {
        codeOf { service.get(ana, "ZZZZZZ") } shouldBe ErrorCode.MESA_INEXISTENTE
        codeOf { service.get(ana, "<script>") } shouldBe ErrorCode.MESA_INEXISTENTE
    }

    @Test
    fun `18_2 iniciar exige todos os lugares preenchidos e so o anfitriao inicia`() {
        val t = service.createTable(ana, rules)
        codeOf { service.start(ana, t.id) } shouldBe ErrorCode.LUGARES_VAGOS
        service.join(beto, t.id, null)
        codeOf { service.start(beto, t.id) } shouldBe ErrorCode.SEM_PERMISSAO
        service.start(ana, t.id).state shouldBe TableState.EM_JOGO
    }

    @Test
    fun `18_2 anfitriao troca lugar vago por robo e inicia`() {
        val t = service.createTable(ana, rules)
        service.seatRobot(ana, t.id, 1).seats[1].kind shouldBe SeatKind.ROBO
        codeOf { service.seatRobot(ana, t.id, 1) } shouldBe ErrorCode.LUGAR_INVALIDO
        service.start(ana, t.id).state shouldBe TableState.EM_JOGO
    }

    @Test
    fun `18_2 so o anfitriao poe robo ou remove humano`() {
        val t = service.createTable(ana, rules.copy(mode = TableMode.DUPLAS))
        service.join(beto, t.id, null)
        codeOf { service.seatRobot(beto, t.id, 2) } shouldBe ErrorCode.SEM_PERMISSAO
        codeOf { service.removeHuman(beto, t.id, 1) } shouldBe ErrorCode.SEM_PERMISSAO
        service.removeHuman(ana, t.id, 1).seats[1].kind shouldBe SeatKind.VAGO
        codeOf { service.removeHuman(ana, t.id, 0) } shouldBe ErrorCode.LUGAR_INVALIDO
    }

    @Test
    fun `18_2 convidado sai da mesa e anfitriao nao sai mas encerra`() {
        val t = service.createTable(ana, rules)
        service.join(beto, t.id, null)
        service.leave(beto, t.id).seats[1].kind shouldBe SeatKind.VAGO
        codeOf { service.leave(ana, t.id) } shouldBe ErrorCode.SEM_PERMISSAO
        codeOf { service.close(beto, t.id) } shouldBe ErrorCode.SEM_PERMISSAO
        service.close(ana, t.id).state shouldBe TableState.ENCERRADA
        codeOf { service.join(beto, t.id, null) } shouldBe ErrorCode.MESA_INEXISTENTE
    }

    @Test
    fun `18_2 regras sao imutaveis e mesa iniciada nao aceita mais ninguem`() {
        val t = service.createTable(ana, rules)
        service.seatRobot(ana, t.id, 1)
        val started = service.start(ana, t.id)
        codeOf { service.join(beto, t.id, null) } shouldBe ErrorCode.MESA_JA_INICIADA
        codeOf { service.leave(beto, t.id) } shouldBe ErrorCode.MESA_JA_INICIADA
        codeOf { service.start(ana, t.id) } shouldBe ErrorCode.MESA_JA_INICIADA
        service.get(ana, t.id).rules shouldBe started.rules
    }

    @Test
    fun `18_2 mesa aberta inativa expira e consulta de quem esta sentado renova`() {
        val a = service.createTable(ana, rules)
        val b = service.createTable(beto, rules)
        now += 20 * 60_000L
        service.get(beto, b.id) // renova
        now += 20 * 60_000L
        codeOf { service.get(ana, a.id) } shouldBe ErrorCode.MESA_INEXISTENTE
        service.get(beto, b.id).id shouldBe b.id
    }

    @Test
    fun `18_2 entradas concorrentes nunca passam do numero de lugares`() {
        val t = service.createTable(ana, rules.copy(mode = TableMode.DUPLAS))
        val threads = (1..40).map { i ->
            Thread { runCatching { service.join(Guest("g$i", "G$i"), t.id, null) } }.also { it.start() }
        }
        threads.forEach { it.join() }
        val v = service.get(ana, t.id)
        v.seats.count { it.kind == SeatKind.HUMANO } shouldBe 4
        v.seats.mapNotNull { it.guestId }.toSet().size shouldBe 4
    }
}
