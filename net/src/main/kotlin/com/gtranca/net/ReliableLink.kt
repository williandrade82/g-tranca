package com.gtranca.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uma conexão confiável sobre o [LocalTransport] (§16.5): numera o que sai, guarda o que ainda não foi confirmado e o
 * **reenvia** quando falta (pedido de reenvio do outro lado, ao ver uma lacuna) ou quando a confirmação não chega a tempo
 * (a última mensagem perdida não deixa lacuna). Confirmações vão junto de toda mensagem ([Envelope.ack]) e, sem nada a
 * enviar, num [AckOnly] depois de [ackDelayMillis]. Os temporizadores só existem enquanto há o que confirmar ou reenviar
 * (nada roda com a conexão parada) e desistem depois de [maxResends] tentativas (a queda é avisada pelo transporte).
 *
 * Todas as funções `...Locked` exigem a [lock] da sessão dona; os temporizadores a tomam sozinhos.
 */
class ReliableLink(
    val endpoint: EndpointId,
    private val transport: LocalTransport,
    private val codec: ProtocolCodec,
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val ackDelayMillis: Long = ACK_DELAY_MILLIS,
    private val resendAfterMillis: Long = RESEND_AFTER_MILLIS,
    private val maxResends: Int = MAX_RESENDS,
) {
    private val link = SequencedLink()
    private val unacked = ArrayDeque<Pair<Long, Payload>>()
    private var ackSent = 0L
    private var ackJob: Job? = null
    private var resendJob: Job? = null
    private var resends = 0
    private var closed = false

    /** Envia [payload] na sequência (será reenviado até ser confirmado). */
    suspend fun sendLocked(payload: Payload) {
        if (closed) return
        val seq = link.nextSeq()
        unacked.addLast(seq to payload)
        transmit(seq, payload)
        armResend()
    }

    /** Processa um envelope recebido; devolve o que está pronto, em ordem (duplicadas e atrasadas somem). */
    suspend fun receiveLocked(envelope: Envelope): List<Payload> {
        if (closed) return emptyList()
        confirm(envelope.ack)
        if (envelope.seq == 0L) {
            (envelope.p as? ResendRequest)?.let { request -> unacked.filter { it.first >= request.from }.forEach { transmit(it.first, it.second) } }
            return emptyList()
        }
        val ready = link.accept(envelope).map { it.p }
        link.missing?.let { control(ResendRequest(it)) }
        if (link.lastInOrder > ackSent) armAck() else if (ready.isEmpty() && link.missing == null) control(AckOnly)
        return ready
    }

    /** Encerra a conexão: nada mais sai nem é reenviado. */
    fun close() {
        closed = true
        ackJob?.cancel()
        resendJob?.cancel()
        unacked.clear()
    }

    private fun confirm(ack: Long) {
        if (ack <= 0) return
        var progressed = false
        while (unacked.isNotEmpty() && unacked.first().first <= ack) {
            unacked.removeFirst()
            progressed = true
        }
        if (progressed) resends = 0
        if (unacked.isEmpty()) {
            resendJob?.cancel()
            resendJob = null
        }
    }

    private suspend fun transmit(seq: Long, payload: Payload) {
        ackSent = link.lastInOrder
        transport.send(endpoint, codec.encode(seq, payload, ackSent))
    }

    private suspend fun control(payload: Payload) {
        ackSent = link.lastInOrder
        transport.send(endpoint, codec.encode(0, payload, ackSent))
    }

    private fun armAck() {
        if (ackJob?.isActive == true) return
        ackJob = scope.launch {
            delay(ackDelayMillis)
            lock.withLock {
                ackJob = null
                if (!closed && link.lastInOrder > ackSent) control(AckOnly)
            }
        }
    }

    private fun armResend() {
        if (resendJob?.isActive == true) return
        resendJob = scope.launch {
            while (true) {
                delay(resendAfterMillis)
                val again = lock.withLock {
                    if (closed || unacked.isEmpty() || resends >= maxResends) {
                        resendJob = null
                        return@withLock false
                    }
                    resends++
                    unacked.toList().forEach { transmit(it.first, it.second) }
                    true
                }
                if (!again) break
            }
        }
    }

    companion object {
        const val ACK_DELAY_MILLIS: Long = 200
        const val RESEND_AFTER_MILLIS: Long = 1_000
        const val MAX_RESENDS: Int = 5
    }
}
