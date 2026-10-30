package com.gtranca.online.client

import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.Welcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import com.gtranca.online.contracts.ServerMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Prova real contra o servidor online (cluster local): DESLIGADA por padrão. Ligue com a variável de ambiente
 * `GTRANCA_ONLINE_SERVER` (ex.: `host` ou `host:porta`; nenhum endereço fica no código):
 *
 *     $env:GTRANCA_ONLINE_SERVER = "<servidor>"; .\gradlew.bat :online-client:test --tests "*Live*" --rerun
 *
 * (variável de ambiente não é entrada do Gradle: use `--rerun` e confira no relatório que o teste EXECUTOU, não "skipped").
 */
@EnabledIfEnvironmentVariable(named = "GTRANCA_ONLINE_SERVER", matches = ".+")
class OnlineLiveIntegrationTest {

    // §18.2 convidados, mesa 1x1, entrada, início, conexão à partida e encerramento pelo anfitrião
    @Test
    fun `dois convidados criam mesa, entram, iniciam e conectam a partida`() = runBlocking<Unit> {
        val server = System.getenv("GTRANCA_ONLINE_SERVER")
        val endpoint = OnlineEndpoint.parse(server, cleartext = true) ?: error("servidor inválido: $server")
        val client = OnlineClient(endpoint, OnlineClient.newHttpClient())
        try {
            val ana = client.createGuest("Ana")
            val beto = client.createGuest("Beto")

            val created = client.createTable(ana.token, TableRules(TableMode.INDIVIDUAL, "{}", targetScore = 200))
            created.state shouldBe TableState.ABERTA
            created.hostId shouldBe ana.id
            created.seats.map { it.kind } shouldBe listOf(SeatKind.HUMANO, SeatKind.VAGO)

            val joined = client.join(beto.token, created.id)
            joined.seats.map { it.kind } shouldBe listOf(SeatKind.HUMANO, SeatKind.HUMANO)
            client.table(ana.token, created.id.lowercase()).seats[1].name shouldBe "Beto"

            shouldThrow<OnlineException> { client.table(ana.token, "ZZZZZZ") }.code shouldBe ErrorCode.MESA_INEXISTENTE
            shouldThrow<OnlineException> { client.start(beto.token, created.id) }.code shouldBe ErrorCode.SEM_PERMISSAO

            client.start(ana.token, created.id).state shouldBe TableState.EM_JOGO

            // Os dois conectam Ã  partida (uma conexÃ£o por jogador): Welcome e a primeira vista.
            val received = listOf(ana, beto).map { guest ->
                val messages = CopyOnWriteArrayList<ServerMessage>()
                val job = async(Dispatchers.Default) {
                    client.matchSession(guest.token, created.id).events().collect { if (it is MatchEvent.Message) messages += it.message }
                }
                messages to job
            }
            withTimeout(20_000) {
                while (received.any { (m, _) -> m.none { it is SeatUpdate } }) delay(50)
            }
            // Encerra a mesa: os dois recebem `closed` e o fluxo termina.
            client.close(ana.token, created.id).state shouldBe TableState.ENCERRADA
            withTimeout(20_000) { received.forEach { (_, job) -> job.await() } }
            for ((messages, _) in received) {
                messages.first().shouldBeInstanceOf<Welcome>()
                messages.last().shouldBeInstanceOf<TableClosed>()
            }
            client.table(ana.token, created.id).state shouldBe TableState.ENCERRADA
        } finally {
            client.shutdown()
        }
    }
}
