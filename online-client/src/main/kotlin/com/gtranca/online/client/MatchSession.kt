package com.gtranca.online.client

import com.gtranca.engine.Action
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.Replaced
import com.gtranca.online.contracts.Resign
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** Quando e quantas vezes tentar reconectar sozinho depois de uma queda (a vez continua contando no servidor, §18.4). */
data class ReconnectPolicy(
    val maxAttempts: Int = 8,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 15_000,
) {
    fun delayFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return minOf(maxDelayMs, initialDelayMs * (1L shl shift))
    }
}

/** O que a conexão da partida entrega à interface. */
sealed interface MatchEvent {
    /**
     * Mensagem do servidor, na ordem de chegada: `Welcome`, `SeatUpdate`, `SeatsChanged`, `Rejected` e as finais
     * (`Replaced`, `YouResigned`, `TableClosed`, `GameError`), depois das quais o fluxo termina sem reconectar.
     */
    data class Message(val message: ServerMessage) : MatchEvent

    /** A conexão caiu sem aviso do servidor: nova tentativa [attempt] daqui a [delayMs]. */
    data class Reconnecting(val attempt: Int, val delayMs: Long, val cause: OnlineException?) : MatchEvent

    /** Fim sem volta: recusa do handshake (versão, token, assento) ou tentativas esgotadas. O fluxo termina. */
    data class Failed(val error: OnlineException) : MatchEvent
}

/**
 * Conexão à partida (`/ws/match/{id}`, §18.3) com o token do convidado nos cabeçalhos do handshake (nunca na URL).
 * [events] é frio: coletar abre a conexão; cancelar a coleta fecha. Queda inesperada reconecta com o MESMO token e recuo
 * crescente; `replaced`, `resigned`, `closed` e `error` encerram sem reconectar (§18.4).
 *
 * Numeração (`seq`): continua entre conexões, `max(local, welcome.lastSeq) + 1`; `stateVersion` é a versão da última vista.
 */
class MatchSession internal constructor(
    private val client: OnlineClient,
    private val token: String,
    val tableId: String,
    private val policy: ReconnectPolicy,
) {
    private val lock = Any()
    private var seq = 0L
    private var lastVersion = -1L

    @Volatile
    private var outbox: SendChannel<ClientMessage>? = null

    /** Há conexão aberta agora (já com o `Welcome`) para enviar jogadas. */
    val isConnected: Boolean get() = outbox != null

    /** Envia a jogada na vista mais recente. `false` = sem conexão agora (nada foi enviado nem consumiu `seq`). */
    suspend fun play(action: Action): Boolean = play(action, lastVersion)

    suspend fun play(action: Action, stateVersion: Long): Boolean = send { PlayAction(nextSeq(), stateVersion, action) }

    /** Desistir (§13.1, §18.4): um robô assume o assento. */
    suspend fun resign(): Boolean = send { Resign(nextSeq()) }

    private suspend fun send(build: () -> ClientMessage): Boolean {
        val channel = outbox ?: return false
        val message = build()
        return try {
            channel.send(message)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun nextSeq(): Long = synchronized(lock) { ++seq }

    fun events(): Flow<MatchEvent> = channelFlow {
        var failures = 0
        var lastError: OnlineException? = null
        while (true) {
            when (val outcome = connectOnce()) {
                Outcome.Final -> return@channelFlow
                is Outcome.Dropped -> {
                    // Se chegou a receber o Welcome, a série de falhas recomeça.
                    failures = if (outcome.welcomed) 1 else failures + 1
                    lastError = outcome.error
                }
                is Outcome.Refused -> {
                    send(MatchEvent.Failed(outcome.error))
                    return@channelFlow
                }
            }
            if (failures > policy.maxAttempts) {
                send(MatchEvent.Failed(lastError ?: OnlineException(OnlineErrorKind.NETWORK)))
                return@channelFlow
            }
            val wait = policy.delayFor(failures)
            send(MatchEvent.Reconnecting(failures, wait, lastError))
            delay(wait)
        }
    }

    private sealed interface Outcome {
        /** Mensagem final do servidor entregue. */
        data object Final : Outcome

        /** Queda; vale reconectar. */
        data class Dropped(val welcomed: Boolean, val error: OnlineException?) : Outcome

        /** Recusa definitiva do handshake. */
        data class Refused(val error: OnlineException) : Outcome
    }

    private suspend fun ProducerScope<MatchEvent>.connectOnce(): Outcome {
        var opened = false
        var welcomed = false
        var final = false
        try {
            client.http.webSocket(
                "${client.endpoint.wsBase}/ws/match/$tableId",
                request = { with(client) { protocol(token) } },
            ) {
                opened = true
                val out = Channel<ClientMessage>(OUTBOX_SIZE)
                val writer = launch {
                    for (m in out) send(Frame.Text(OnlineJson.encodeToString(ClientMessage.serializer(), m)))
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = try {
                            OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())
                        } catch (e: SerializationException) {
                            continue // mensagem desconhecida (servidor mais novo): ignora
                        }
                        when (message) {
                            is Welcome -> {
                                synchronized(lock) { seq = maxOf(seq, message.lastSeq) }
                                welcomed = true
                                outbox = out
                            }
                            is SeatUpdate -> synchronized(lock) { lastVersion = message.view.version }
                            else -> Unit
                        }
                        this@connectOnce.send(MatchEvent.Message(message))
                        if (message is Replaced || message is YouResigned || message is TableClosed || message is GameError) {
                            final = true
                            break
                        }
                    }
                } finally {
                    outbox = null
                    out.close()
                    writer.cancel()
                }
                if (final) close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (final) return Outcome.Final
            if (opened) return Outcome.Dropped(welcomed, OnlineException(OnlineErrorKind.NETWORK, cause = e))
            return diagnose(e)
        }
        return if (final) Outcome.Final else Outcome.Dropped(welcomed, null)
    }

    /**
     * O handshake falhou: o Ktor só informa "não foi 101". Uma consulta simples ao mesmo endereço, com os mesmos
     * cabeçalhos, traz o status e o `ApiError` (426, 401, 403, 404, 503...).
     */
    private suspend fun diagnose(cause: Exception): Outcome {
        val response = try {
            client.http.request("${client.endpoint.httpBase}/ws/match/$tableId") { with(client) { protocol(token) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Dropped(false, OnlineException(OnlineErrorKind.NETWORK, cause = cause))
        }
        val status = response.status.value
        if (status < 400) return Outcome.Dropped(false, OnlineException(OnlineErrorKind.NETWORK, status = status, cause = cause))
        val error = client.failure(status, runCatching { response.bodyAsText() }.getOrDefault(""))
        return if (error.retryable) Outcome.Dropped(false, error) else Outcome.Refused(error)
    }

    private companion object {
        const val OUTBOX_SIZE = 16
    }
}
