package com.gtranca.net

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresencePhotosTest {

    @Test
    fun `§16_2 so a menor chave abre a conexao e os dois trocam as fotos nela - so chave e foto trafegam`() = runTest {
        val network = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        network.tap = { sent += it; listOf(it) }
        val ta = network.transport()
        val tb = network.transport()
        ta.advertisePresence(PresenceAdvert("ka", "Ana"))
        tb.advertisePresence(PresenceAdvert("kb", "Bia"))
        val ana = PresencePhotos(ta, "ka", myPhoto = "FOTOANA")
        val bia = PresencePhotos(tb, "kb", myPhoto = null)
        val scope = sessionScope
        val jobs = listOf(
            scope.launch { ana.serve() }, scope.launch { bia.serve() },
            scope.launch { ana.fetchAll(ta.discoverPresence()) }, scope.launch { bia.fetchAll(tb.discoverPresence()) },
        )
        runCurrent()
        bia.photos.value shouldBe mapOf("ka" to "FOTOANA")
        ana.photos.value shouldBe mapOf("kb" to "")
        // Uma conexão só (aberta por "ka"): uma mensagem de cada lado, sem nomes.
        val texts = sent.map { it.bytes.decodeToString() }
        texts.size shouldBe 2
        texts.forEach { it shouldNotContain "Ana"; it shouldNotContain "Bia" }
        sent.first().from shouldBe ta.id
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `§16_2 §16_15 sem resposta tenta de novo com espera crescente enquanto a presenca esta visivel`() = runTest {
        val network = MemoryNetwork()
        val ta = network.transport()
        val mute = network.transport()
        mute.advertisePresence(PresenceAdvert("kz", "Mudo")) // não atende (sem serve)
        val ana = PresencePhotos(ta, "ka", myPhoto = null)
        var tries = 0
        network.tap = { d -> if (d.from == ta.id) tries++; listOf(d) }
        val scope = sessionScope
        val jobs = listOf(scope.launch { ana.serve() }, scope.launch { ana.fetchAll(ta.discoverPresence()) })
        runCurrent()
        tries shouldBe 1
        // 10 s de espera + 3 s; depois 10 s + 6 s.
        advanceTimeBy(PresencePhotos.PHOTO_TIMEOUT_MILLIS + 3_000 + 1)
        runCurrent()
        tries shouldBe 2
        advanceTimeBy(PresencePhotos.PHOTO_TIMEOUT_MILLIS + 6_000 + 1)
        runCurrent()
        tries shouldBe 3
        // Sem limite fixo enquanto visível; quando some, para.
        advanceTimeBy(200_000)
        runCurrent()
        tries shouldBeGreaterThan 5
        mute.stopPresence()
        runCurrent()
        val before = tries
        advanceTimeBy(200_000)
        runCurrent()
        tries shouldBe before
        jobs.forEach { it.cancel() }
    }
}
