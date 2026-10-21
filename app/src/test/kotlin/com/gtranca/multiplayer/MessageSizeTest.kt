package com.gtranca.multiplayer

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.net.Decoded
import com.gtranca.net.MemoryNetwork
import com.gtranca.net.ProtocolCodec
import io.kotest.matchers.ints.shouldBeLessThan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Etapa 5: tamanho das mensagens numa partida completa (3 convidados, com o estado do sucessor, §16.10), contra o teto
 * do payload BYTES do Nearby (1 047 552 bytes no AAR 19.5.1). Folga de 8x: sem fragmentação.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSizeTest {
    @Test
    fun `nenhuma mensagem de uma partida completa chega perto do limite do Nearby`() = runTest {
        val seed = 7L
        val network = MemoryNetwork()
        val largest = mutableMapOf<String, Int>()
        val codec = ProtocolCodec()
        network.tap = { d ->
            val type = (codec.decode(d.bytes) as? Decoded.Ok)?.envelope?.p?.let { it::class.simpleName } ?: "?"
            largest[type] = maxOf(largest[type] ?: 0, d.bytes.size)
            listOf(d)
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        println("Maiores mensagens (bytes): " + largest.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        largest.values.max() shouldBeLessThan 1_047_552 / 8
    }
}
