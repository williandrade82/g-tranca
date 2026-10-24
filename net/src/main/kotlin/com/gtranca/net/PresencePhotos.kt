package com.gtranca.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * §16.2 fotos dos jogadores próximos, fora de mesa: quem acha uma presença nova abre uma conexão curta, envia
 * [PhotoRequest], recebe [PhotoReply] e desconecta; quem anuncia a presença atende o pedido com a própria foto (e nada
 * mais). Uma vez por presença, no máximo [maxAttempts] tentativas de [timeoutMillis] (§16.15). As fotos ficam **só em
 * memória** ([photos], por chave de presença; "" = sem foto) e nunca são registradas.
 *
 * Enquanto [serve] roda, ele é o único leitor dos eventos do transporte: pare-o antes de abrir ou entrar numa mesa.
 */
class PresencePhotos(
    private val transport: LocalTransport,
    private val myPhoto: String?,
    private val cache: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val timeoutMillis: Long = PHOTO_TIMEOUT_MILLIS,
    private val maxAttempts: Int = PHOTO_ATTEMPTS,
) {
    /** Chave de presença → foto Base64 ("" = o jogador não tem foto). */
    val photos: StateFlow<Map<String, String>> = cache

    private val waiters = ConcurrentHashMap<EndpointId, CompletableDeferred<PhotoReply?>>()
    private val tried = ConcurrentHashMap<String, Int>()

    /** Atende os pedidos de foto e entrega as respostas aos [fetch] em andamento. Roda até ser cancelado. */
    suspend fun serve() {
        transport.events.collect { event ->
            when (event) {
                is TransportEvent.Received -> {
                    val envelope = (codec.decode(event.bytes) as? Decoded.Ok)?.envelope ?: return@collect
                    when (val p = envelope.p) {
                        PhotoRequest -> transport.send(event.endpoint, codec.encode(1, PhotoReply(myPhoto?.takeIf { it.length <= MAX_PHOTO_BASE64 })))
                        is PhotoReply -> waiters[event.endpoint]?.complete(p)
                        else -> Unit // fora de mesa, nada além da foto é atendido
                    }
                }
                is TransportEvent.Disconnected -> waiters[event.endpoint]?.complete(null)
                is TransportEvent.Connected -> Unit
            }
        }
    }

    /** Pede a foto de [presence] (se ainda não tem e não esgotou as tentativas). Devolve se ficou conhecida. */
    suspend fun fetch(presence: PresenceAdvert): Boolean {
        if (presence.key in cache.value) return true
        val attempts = tried.merge(presence.key, 1, Int::plus)!!
        if (attempts > maxAttempts) return false
        val endpoint = runCatching { transport.connectPresence(presence) }.getOrNull() ?: return false
        val waiter = CompletableDeferred<PhotoReply?>()
        waiters[endpoint] = waiter
        try {
            transport.send(endpoint, codec.encode(1, PhotoRequest))
            val reply = withTimeoutOrNull(timeoutMillis) { waiter.await() } ?: return false
            cache.update { it + (presence.key to (reply.photo?.takeIf { it.length <= MAX_PHOTO_BASE64 } ?: "")) }
            return true
        } finally {
            waiters.remove(endpoint)
            runCatching { transport.disconnect(endpoint) }
        }
    }

    /** Pede a foto de cada presença nova que aparecer em [presences] (uma por vez por presença). */
    suspend fun fetchAll(presences: Flow<List<PresenceAdvert>>) = coroutineScope {
        val running = ConcurrentHashMap.newKeySet<String>()
        presences.collect { list ->
            list.filter { it.key !in cache.value && running.add(it.key) }.forEach { p ->
                launch {
                    try {
                        while (!fetch(p) && (tried[p.key] ?: 0) < maxAttempts) Unit
                    } finally {
                        running.remove(p.key)
                    }
                }
            }
        }
    }

    companion object {
        /** §16.15 espera da resposta da foto por tentativa. */
        const val PHOTO_TIMEOUT_MILLIS: Long = 10_000

        /** §16.15 tentativas por presença. */
        const val PHOTO_ATTEMPTS: Int = 2
    }
}
