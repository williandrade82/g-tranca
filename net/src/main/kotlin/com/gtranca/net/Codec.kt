package com.gtranca.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Resultado da leitura de uma mensagem recebida. */
sealed interface Decoded {
    data class Ok(val envelope: Envelope) : Decoded

    /** §16.1 o remetente usa outra versão do protocolo: recusar com [VERSION_MISMATCH_MESSAGE]. */
    data class VersionMismatch(val remoteVersion: Int?) : Decoded

    /** Bytes ilegíveis (não é uma mensagem do jogo). */
    data object Malformed : Decoded
}

/**
 * Codificação das mensagens (JSON em UTF-8). A versão ([Envelope.v]) é lida do JSON cru **antes** de decodificar o
 * conteúdo: uma mensagem de outra versão, com tipos que esta não conhece, ainda é recusada com a mensagem clara.
 */
class ProtocolCodec(val version: Int = PROTOCOL_VERSION) {

    fun encode(seq: Long, payload: Payload, ack: Long = 0): ByteArray =
        json.encodeToString(Envelope.serializer(), Envelope(version, seq, payload, ack)).encodeToByteArray()

    fun decode(bytes: ByteArray): Decoded {
        val element = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
            ?: return Decoded.Malformed
        val remote = runCatching { element["v"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (remote != version) return Decoded.VersionMismatch(remote)
        return runCatching { Decoded.Ok(json.decodeFromJsonElement(Envelope.serializer(), element)) }
            .getOrElse { Decoded.Malformed }
    }

    companion object {
        /** Mensagens pequenas: sem valores padrão; campos novos desconhecidos são ignorados. */
        val json: Json = Json {
            classDiscriminator = "t"
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Números de sequência de uma conexão (§16.5): numera o que sai e, no que chega, descarta duplicadas e atrasadas e
 * entrega em ordem (as que chegam adiantadas esperam as anteriores; [missing] diz qual falta, para pedir o reenvio).
 * Não é seguro para uso concorrente: quem usa sincroniza.
 */
class SequencedLink {
    private var nextOut = 1L
    private var expectedIn = 1L
    private val early = sortedMapOf<Long, Envelope>()

    fun nextSeq(): Long = nextOut++

    /** Última mensagem recebida em ordem (a confirmação que vai no [Envelope.ack]). */
    val lastInOrder: Long get() = expectedIn - 1

    /** A mensagem que falta (há adiantadas esperando por ela), ou `null`. */
    val missing: Long? get() = if (early.isEmpty()) null else expectedIn

    /** Envelopes prontos para processar, em ordem; vazio se [envelope] é duplicado, atrasado ou chegou adiantado. */
    fun accept(envelope: Envelope): List<Envelope> {
        if (envelope.seq < expectedIn || envelope.seq in early) return emptyList()
        if (envelope.seq > expectedIn) {
            if (early.size < MAX_EARLY) early[envelope.seq] = envelope
            return emptyList()
        }
        val ready = mutableListOf(envelope)
        expectedIn++
        while (true) {
            val next = early.remove(expectedIn) ?: break
            ready += next
            expectedIn++
        }
        return ready
    }

    private companion object {
        const val MAX_EARLY = 256
    }
}
