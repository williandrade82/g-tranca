package com.gtranca.online.game

import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.LobbyLimits
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.GameEndReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Erro ao criar a partida: [status] HTTP e mensagem. */
class MatchCreateException(val status: Int, override val message: String) : RuntimeException(message)

/** Partidas em andamento neste pod (so memoria; o resultado e publicado ao terminar nas fases seguintes). */
class MatchRegistry(
    private val config: GameConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val notifier: LobbyNotifier = LobbyNotifier.None,
    /** Esperas entre as tentativas de avisar o lobby (a 1a tentativa e imediata). */
    private val notifyRetryDelaysMs: List<Long> = listOf(500, 2_000, 5_000, 15_000),
) {
    private val log = LoggerFactory.getLogger(MatchRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, MatchSession>()
    private val secure = SecureRandom()

    val count: Int get() = sessions.size

    fun find(rawId: String): MatchSession? = TableId.normalize(rawId)?.let { sessions[it] }

    /**
     * Cria a partida. Idempotente por mesa: repetir o pedido de uma partida em andamento devolve a mesma (`created`
     * = false), para o lobby poder repetir depois de uma falha de rede.
     */
    @Synchronized
    fun create(req: CreateMatchRequest): Pair<MatchSession, Boolean> {
        fun bad(text: String): Nothing = throw MatchCreateException(400, text)
        val id = TableId.normalize(req.tableId) ?: bad("Id de mesa inválido.")
        sessions[id]?.let { if (!it.isFinished) return it to false }
        val mode = req.rules.mode
        if (req.seats.size != mode.seats) bad("A mesa precisa de ${mode.seats} assentos.")
        val humans = req.seats.filterIsInstance<SeatSpec.Human>()
        if (humans.isEmpty()) bad("A mesa precisa de ao menos um jogador humano.")
        if (humans.map { it.guestId }.toSet().size != humans.size) bad("Jogador repetido na mesa.")
        if (humans.any { it.guestId.isBlank() || it.name.isBlank() }) bad("Jogador inválido.")
        if (req.rules.rulesJson.length > LobbyLimits.MAX_RULES_JSON) bad("Regras grandes demais.")
        if (req.rules.targetScore !in 1..LobbyLimits.MAX_TARGET_SCORE) bad("Pontuação-alvo inválida.")
        sessions.values.removeIf { it.isFinished }
        if (sessions.size >= config.maxMatches) throw MatchCreateException(503, "Servidor cheio. Tente de novo em instantes.")
        val session = try {
            MatchSession(
                id, req.rules, req.seats, req.seed ?: secure.nextLong(), config,
                onEnded = { end -> scope.launch { notifyLobby(id, end.reason) } },
            )
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "Regras inválidas.")
        }
        sessions[id] = session
        scope.launch {
            try {
                session.run()
            } finally {
                sessions.remove(id, session)
            }
        }
        return session to true
    }

    /** O anfitriao encerra a mesa em jogo (§18.4). `null` = nao ha partida com esse id neste pod. */
    suspend fun closeMatch(rawId: String, hostGuestId: String): CloseResult? = find(rawId)?.closeByHost(hostGuestId)

    /** Avisa o lobby (fora do ator da partida) com algumas tentativas; falhou tudo = o TTL da mesa no lobby limpa depois. */
    private suspend fun notifyLobby(tableId: String, reason: GameEndReason) {
        for (attempt in 0..notifyRetryDelaysMs.size) {
            try {
                notifier.matchEnded(tableId, reason)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Aviso ao lobby falhou table={} tentativa={}: {}", tableId, attempt + 1, e.message)
                if (attempt < notifyRetryDelaysMs.size) delay(notifyRetryDelaysMs[attempt])
            }
        }
    }

    fun close() {
        sessions.values.forEach { it.shutdown() }
        scope.cancel()
    }
}
