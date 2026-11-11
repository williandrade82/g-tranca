package com.gtranca.online.game

import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.PayoutKind
import com.gtranca.online.contracts.LobbyLimits
import com.gtranca.online.contracts.PresenceNotice
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.GameEndReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
    /** Apostas (§18.2, §14.6). `null` = sem economia: a aposta so e registrada (testes antigos). */
    private val economy: EconomyClient? = null,
    /** Esperas entre as tentativas de liquidar (idempotente; esgotadas, a aposta fica RETIDA para o operador, §18.7). */
    private val settleRetryDelaysMs: List<Long> = listOf(1_000, 5_000, 15_000, 60_000, 300_000, 900_000),
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
    /**
     * Cria a partida retendo antes a aposta de todos os humanos (§18.2), numa chamada so a economia com um id de aposta
     * NOVO por jogo (o id da mesa se repete). Falha de rede com a economia = o jogo NAO comeca (503, a mesa segue ABERTA)
     * e a retencao eventualmente feita e devolvida. Quem nao tem mais saldo joga sem aposta (§14.6).
     */
    suspend fun createWithBets(req: CreateMatchRequest): Pair<MatchSession, Boolean> {
        find(req.tableId)?.let { if (!it.isFinished) return it to false }
        val eco = economy
        val bet = req.rules.bet.toLong()
        val humans = req.seats.filterIsInstance<SeatSpec.Human>().map { it.guestId }
        if (eco == null || bet <= 0 || humans.isEmpty()) return create(req)
        val id = TableId.normalize(req.tableId) ?: throw MatchCreateException(400, "Id de mesa inválido.")
        val betId = "$id-" + java.lang.Long.toUnsignedString(secure.nextLong(), 36)
        val held = try {
            eco.hold(betId, bet, humans)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Retencao da aposta falhou table={}: {}", id, e.message)
            // A retencao pode ter sido gravada (tempo esgotado): devolve em segundo plano, idempotente.
            scope.launch { settleWithRetry(id, betId, humans.map { BetPayout(it, PayoutKind.DEVOLUCAO, bet) }) }
            throw MatchCreateException(503, "Não foi possível reservar as apostas agora. Tente de novo.")
        }
        val (session, created) = try {
            create(req, betId to held)
        } catch (e: MatchCreateException) {
            scope.launch { settleWithRetry(id, betId, held.map { BetPayout(it, PayoutKind.DEVOLUCAO, bet) }) }
            throw e
        }
        if (!created) scope.launch { settleWithRetry(id, betId, held.map { BetPayout(it, PayoutKind.DEVOLUCAO, bet) }) }
        return session to created
    }

    @Synchronized
    fun create(req: CreateMatchRequest, bets: Pair<String, Set<String>>? = null): Pair<MatchSession, Boolean> {
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
        val presence = MutableStateFlow<PresenceNotice?>(null)
        // §17.3: id unico por JOGO para a chave de idempotencia do debito de boosters — o id da mesa se repete entre
        // jogos sucessivos (§18.2), igual ao betId das apostas (criado ali do mesmo jeito, so que por essa aposta).
        val boosterMatchId = "$id-bst-" + java.lang.Long.toUnsignedString(secure.nextLong(), 36)
        val session = try {
            MatchSession(
                id, req.rules, req.seats, req.seed ?: secure.nextLong(), config,
                boosterMatchId = boosterMatchId,
                onEnded = { end ->
                    scope.launch { notifyLobby(id, end.reason) }
                    if (bets != null && bets.second.isNotEmpty()) {
                        val payouts = settleBets(end, bets.second, req.rules.bet.toLong())
                        scope.launch { settleWithRetry(id, bets.first, payouts) }
                    }
                },
                onPresence = { connected, abandoned -> presence.value = PresenceNotice(connected.sorted(), abandoned.sorted()) },
                economy = economy,
                boosterDeadlineMs = config.boosterSelectMs,
                scope = scope,
            )
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "Regras inválidas.")
        }
        sessions[id] = session
        // Presenca ao lobby: um coletor por partida, em ordem e so com o estado mais recente (StateFlow e conflado).
        val sender = scope.launch {
            presence.filterNotNull().collect { notice ->
                try {
                    notifier.presence(id, notice)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Presenca ao lobby falhou table={}: {}", id, e.message)
                }
            }
        }
        scope.launch {
            try {
                session.run()
            } finally {
                sessions.remove(id, session)
                sender.cancel()
            }
        }
        return session to true
    }

    /** O anfitriao encerra a mesa em jogo (§18.4). `null` = nao ha partida com esse id neste pod. */
    suspend fun closeMatch(rawId: String, hostGuestId: String): CloseResult? = find(rawId)?.closeByHost(hostGuestId)

    /**
     * O OPERADOR (console de gerenciamento, §18.7) encerra a mesa travada - ninguem e punido, a aposta de todos
     * os humanos volta. `null` = nao ha partida com esse id neste pod.
     */
    suspend fun closeMatchByOperator(rawId: String, reason: String?): CloseResult? = find(rawId)?.closeByOperator(reason)

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

    /** Liquida com retentativas (a economia ignora repeticoes). Esgotadas: so log; a aposta fica RETIDA (§18.7). */
    private suspend fun settleWithRetry(tableId: String, betId: String, payouts: List<BetPayout>) {
        if (payouts.isEmpty()) return
        val eco = economy ?: return
        for (attempt in 0..settleRetryDelaysMs.size) {
            try {
                eco.settle(betId, payouts)
                log.info("Aposta liquidada table={} bet={} contas={}", tableId, betId, payouts.size)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is EconomyUnavailableException && e.permanent) {
                    log.error("Liquidacao recusada pela economia table={} bet={}: {}", tableId, betId, e.message)
                    return
                }
                log.warn("Liquidacao falhou table={} bet={} tentativa={}: {}", tableId, betId, attempt + 1, e.message)
                if (attempt < settleRetryDelaysMs.size) delay(settleRetryDelaysMs[attempt])
            }
        }
        log.error("Aposta ficou RETIDA (liquidacao esgotou as tentativas) table={} bet={}", tableId, betId)
    }

    fun close() {
        sessions.values.forEach { it.shutdown() }
        scope.cancel()
    }
}
