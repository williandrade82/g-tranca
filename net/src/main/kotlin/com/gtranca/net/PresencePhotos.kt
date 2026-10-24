package com.gtranca.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * §16.2 fotos dos jogadores próximos, fora de mesa. Dois aparelhos que se acham trocam as fotos numa conexão curta
 * aberta **só pelo de menor chave** de presença (os dois chamarem ao mesmo tempo colide no Nearby): quem abre envia
 * [PresencePhoto] com a sua chave e foto, o outro guarda e responde com as dele, e quem abriu desconecta. Sem sucesso, nova
 * tentativa com espera crescente ([BACKOFF_MILLIS]) enquanto a presença estiver visível. As fotos ficam **só em memória**
 * ([photos], por chave; "" = sem foto) e nunca são registradas.
 *
 * Enquanto [serve] roda, ele é o único leitor dos eventos do transporte: pare-o antes de abrir ou entrar numa mesa.
 */
class PresencePhotos(
    private val transport: LocalTransport,
    private val myKey: String,
    private val myPhoto: String?,
    private val cache: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val timeoutMillis: Long = PHOTO_TIMEOUT_MILLIS,
) {
    /** Chave de presença → foto Base64 ("" = o jogador não tem foto). */
    val photos: StateFlow<Map<String, String>> = cache

    private val waiters = ConcurrentHashMap<EndpointId, CompletableDeferred<PresencePhoto?>>()

    private fun mine() = PresencePhoto(myKey, myPhoto?.takeIf { it.length <= MAX_PHOTO_BASE64 })

    private fun remember(photo: PresencePhoto) {
        if (photo.key.isEmpty() || photo.key == myKey) return
        cache.update { it + (photo.key to (photo.photo?.takeIf { p -> p.length <= MAX_PHOTO_BASE64 } ?: "")) }
    }

    /** Responde às trocas abertas pelos outros e entrega as respostas às trocas deste aparelho. Roda até ser cancelado. */
    suspend fun serve() {
        transport.events.collect { handle(it) }
    }

    /** Trata um evento do transporte (quem já lê os eventos, como [Recruitment], repassa os de foto). */
    suspend fun handle(event: TransportEvent) {
        run {
            when (event) {
                is TransportEvent.Received -> {
                    val p = ((codec.decode(event.bytes) as? Decoded.Ok)?.envelope?.p as? PresencePhoto) ?: return
                    val waiter = waiters[event.endpoint]
                    if (waiter != null) {
                        waiter.complete(p)
                    } else {
                        // Troca aberta pelo outro: guarda a foto dele e responde com a deste aparelho.
                        remember(p)
                        transport.send(event.endpoint, codec.encode(1, mine()))
                    }
                }
                is TransportEvent.Disconnected -> waiters[event.endpoint]?.complete(null)
                is TransportEvent.Connected -> Unit // fora de mesa, nada além da foto é atendido
            }
        }
    }

    /** Uma tentativa de troca com [presence] (só se este aparelho tem a menor chave). Devolve se deu certo. */
    suspend fun exchange(presence: PresenceAdvert): Boolean {
        if (presence.key in cache.value) return true
        if (!initiates(presence.key)) return false
        val endpoint = runCatching { transport.connectPresence(presence) }.getOrNull() ?: return false
        val waiter = CompletableDeferred<PresencePhoto?>()
        waiters[endpoint] = waiter
        try {
            transport.send(endpoint, codec.encode(1, mine()))
            val reply = withTimeoutOrNull(timeoutMillis) { waiter.await() } ?: return false
            if (reply.key != presence.key) return false
            remember(reply)
            return true
        } finally {
            waiters.remove(endpoint)
            runCatching { transport.disconnect(endpoint) }
        }
    }

    /** §16.2 só o de menor chave abre a conexão. */
    fun initiates(otherKey: String): Boolean = myKey < otherKey

    /**
     * Para cada presença visível em [presences] cuja foto falta e que cabe a este aparelho iniciar: tenta, e repete com
     * espera crescente enquanto ela continuar visível (§16.15).
     */
    suspend fun fetchAll(presences: Flow<List<PresenceAdvert>>) = coroutineScope {
        val running = ConcurrentHashMap<String, Job>()
        val visible = MutableStateFlow<Map<String, PresenceAdvert>>(emptyMap())
        presences.collect { list ->
            val now = list.filter { !it.leaving }.associateBy { it.key }
            visible.value = now
            running.keys.filter { it !in now }.forEach { running.remove(it)?.cancel() }
            now.values.filter { it.key !in cache.value && initiates(it.key) && !running.containsKey(it.key) }.forEach { p ->
                running[p.key] = launch {
                    var attempt = 0
                    while (p.key !in cache.value) {
                        val current = visible.value[p.key] ?: break
                        if (exchange(current)) break
                        delay(BACKOFF_MILLIS[minOf(attempt++, BACKOFF_MILLIS.lastIndex)])
                    }
                    running.remove(p.key)
                }
            }
        }
    }

    companion object {
        /** §16.15 espera da resposta da troca por tentativa. */
        const val PHOTO_TIMEOUT_MILLIS: Long = 10_000

        /** §16.15 espera antes de cada nova tentativa: 3 s, 6 s, 12 s, 24 s e, daí em diante, 30 s. */
        val BACKOFF_MILLIS: List<Long> = listOf(3_000, 6_000, 12_000, 24_000, 30_000)
    }
}
