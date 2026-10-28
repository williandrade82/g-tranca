package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** §16.5 a mensagem perdida é reenviada: nem uma lacuna no meio nem a perda da última travam a conexão. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliableLinkTest {

    private val rules = TableRules("Mesa", GameMode.DUPLAS, RuleSet.DEFAULT, 1000, 0, "facil")

    private fun typeOf(d: Delivery): String? = runCatching {
        (ProtocolCodec().decode(d.bytes) as Decoded.Ok).envelope.p::class.simpleName
    }.getOrNull()

    @Test
    fun `§16_5 lacuna no meio e perda da ultima mensagem sao recuperadas pelo reenvio`() = runTest {
        val network = MemoryNetwork()
        val dropped = mutableListOf<String>()
        var dropNextRoster = 0
        network.tap = { d ->
            val type = typeOf(d)
            if (type == "RosterUpdate" && dropNextRoster > 0) {
                dropNextRoster--
                dropped += type
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = plannedTable(mapOf(1 to "Ana"), network = network, start = false)
        val ana = t.guests.getValue(1)
        val host = t.host
        advanceUntilIdle()
        // Perde a 1ª atualização da sala (lacuna, a 2ª chega adiantada) e, depois, a última (sem sucessora).
        dropNextRoster = 1
        ana.rename("Ana Maria")
        advanceUntilIdle()
        ana.rename("Ana Maria 2")
        advanceUntilIdle()
        dropNextRoster = 1
        ana.rename("Ana Maria 3")
        advanceUntilIdle()
        dropped.size shouldBe 2
        ana.roster.value shouldBe host.roster.value
        ana.roster.value.last().name shouldBe "Ana Maria 3"
    }

    @Test
    fun `§16_5 duplicada e atrasada sao descartadas e as confirmacoes param os reenvios`() = runTest {
        val network = MemoryNetwork()
        var sentToGuest = 0
        network.tap = { d -> if (d.to.value == "e2") sentToGuest++; listOf(d, d) }
        val t = plannedTable(mapOf(1 to "Ana"), network = network, start = false)
        val ana = t.guests.getValue(1)
        val host = t.host
        advanceUntilIdle()
        ana.rename("Bia")
        advanceUntilIdle()
        ana.roster.value shouldBe host.roster.value
        // Nada fica pendurado: depois de tudo confirmado não há reenvio (o relógio virtual parou).
        val before = sentToGuest
        advanceUntilIdle()
        sentToGuest shouldBe before
    }
}
