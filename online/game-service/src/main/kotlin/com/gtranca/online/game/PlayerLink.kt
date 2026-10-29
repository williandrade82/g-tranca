package com.gtranca.online.game

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Ponta de saida de UMA conexao de jogador: o ator da partida so entrega mensagens aqui (nunca bloqueia num cliente
 * lento); quem escreve no WebSocket (ou o teste) le [messages]. Fila cheia = cliente lento demais: a conexao e fechada
 * (ele reconecta e recebe a vista atual).
 */
class PlayerLink(capacity: Int = 128) {
    class Closing(val code: Int, val reason: String)

    private val out = Channel<ServerMessage>(capacity)
    private val closing = AtomicReference<Closing?>(null)

    val messages: ReceiveChannel<ServerMessage> get() = out

    /** Por que e com que codigo a conexao deve ser fechada (depois de esvaziar [messages]). */
    val closeInfo: Closing? get() = closing.get()

    fun offer(message: ServerMessage): Boolean {
        if (closing.get() != null) return false
        if (out.trySend(message).isSuccess) return true
        close(1013, "Conexao lenta demais")
        return false
    }

    fun close(code: Int = 1000, reason: String = "") {
        closing.compareAndSet(null, Closing(code, reason))
        out.close()
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Perfil de regras da mesa (§14.4); o lobby so limita o tamanho, quem valida com o motor e o game-service. */
internal fun TableRules.ruleSetOrThrow(): RuleSet = try {
    lenientJson.decodeFromString(RuleSet.serializer(), rulesJson)
} catch (e: SerializationException) {
    throw IllegalArgumentException("rulesJson inválido")
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("rulesJson inválido")
}

internal fun TableMode.toEngine(): GameMode = when (this) {
    TableMode.INDIVIDUAL -> GameMode.INDIVIDUAL
    TableMode.DUPLAS -> GameMode.DUPLAS
}
