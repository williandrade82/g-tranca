package com.gtranca.net

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
    fun `§16_2 a foto do jogador proximo e pedida uma vez e so a foto trafega - sem foto, vazio`() = runTest {
        val network = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        network.tap = { sent += it; listOf(it) }
        val ta = network.transport()
        val tb = network.transport()
        ta.advertisePresence(PresenceAdvert("ka", "Ana"))
        tb.advertisePresence(PresenceAdvert("kb", "Bia"))
        val ana = PresencePhotos(ta, myPhoto = "FOTOANA")
        val bia = PresencePhotos(tb, myPhoto = null)
        val scope = sessionScope
        val jobs = listOf(
            scope.launch { ana.serve() }, scope.launch { bia.serve() },
            scope.launch { ana.fetchAll(ta.discoverPresence()) }, scope.launch { bia.fetchAll(tb.discoverPresence()) },
        )
        runCurrent()
        bia.photos.value shouldBe mapOf("ka" to "FOTOANA")
        ana.photos.value shouldBe mapOf("kb" to "")
        // Uma vez por presença: duas respostas e dois pedidos, e nada além disso (nem nome, nem token).
        val texts = sent.map { it.bytes.decodeToString() }
        texts.count { "photoReq" in it } shouldBe 2
        texts.count { "photoRep" in it } shouldBe 2
        texts.forEach { it shouldNotContain "Ana"; it shouldNotContain "Bia" }
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `§16_2 §16_15 sem resposta, no maximo 2 tentativas de 10 s por presenca`() = runTest {
        val network = MemoryNetwork()
        val ta = network.transport()
        val mute = network.transport()
        mute.advertisePresence(PresenceAdvert("km", "Mudo")) // não atende (sem serve)
        val ana = PresencePhotos(ta, myPhoto = null)
        val scope = sessionScope
        var connects = 0
        network.tap = { d -> if ("photoReq" in d.bytes.decodeToString()) connects++; listOf(d) }
        val jobs = listOf(scope.launch { ana.serve() }, scope.launch { ana.fetchAll(ta.discoverPresence()) })
        runCurrent()
        advanceTimeBy(PresencePhotos.PHOTO_TIMEOUT_MILLIS * 5)
        runCurrent()
        connects shouldBe PresencePhotos.PHOTO_ATTEMPTS
        ana.photos.value shouldBe emptyMap()
        jobs.forEach { it.cancel() }
    }
}
