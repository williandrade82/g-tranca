package com.gtranca.online.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ATENCAO: endpoints `/internal/...` sao SO PARA VALIDACAO LOCAL (sem autenticacao). Serao removidos ou
 * fechados (rede interna/autenticacao) antes de qualquer exposicao fora do cluster local.
 */
object SimLimits {
    const val MAX_GAMES = 200
    const val MAX_MILLIS = 120_000L
}

@Serializable
data class SimRequest(
    val games: Int = 10,
    val seed: Long = 42,
    val mode: String = "individual",
    val difficulty: String = "facil",
    val rulesJson: String? = null,
    val targetScore: Int? = null,
)

@Serializable
data class SimSummary(
    val games: Int,
    val finished: Int,
    val winsBySide: List<Int>,
    val rounds: Int,
    val actions: Int,
    val millis: Long,
    /** Jogos com jogada ilegal, excecao, beco sem saida ou travamento. Deve ser 0. */
    val failures: Int,
    val failureSamples: List<String> = emptyList(),
    /** `true` se o tempo maximo acabou antes de [games] jogos. */
    val timedOut: Boolean = false,
)

@Serializable
data class SimError(val error: String)

/** Valida o pedido e devolve a configuracao-base (sem a semente do jogo) ou a mensagem de erro. */
internal fun parseSimRequest(req: SimRequest): Result<MatchConfig> = runCatching {
    require(req.games in 1..SimLimits.MAX_GAMES) { "games deve estar entre 1 e ${SimLimits.MAX_GAMES}" }
    val mode = when (req.mode.lowercase()) {
        "individual" -> GameMode.INDIVIDUAL
        "duplas" -> GameMode.DUPLAS
        else -> throw IllegalArgumentException("mode deve ser individual ou duplas")
    }
    val difficulty = Difficulty.entries.firstOrNull { it.id == req.difficulty.lowercase() }
        ?: throw IllegalArgumentException("difficulty deve ser facil, medio ou dificil")
    val rulesJson = req.rulesJson?.takeIf { it.isNotBlank() }
    require(rulesJson == null || rulesJson.length <= 8_000) { "rulesJson grande demais" }
    val rules = if (rulesJson == null) RuleSet.DEFAULT else try {
        Json.decodeFromString(RuleSet.serializer(), rulesJson)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("rulesJson invalido")
    }
    req.targetScore?.let { require(it in 1..100_000) { "targetScore invalido" } }
    MatchConfig(mode = mode, difficulty = difficulty, rules = rules, targetScore = req.targetScore)
}

/** Joga `games` jogos (o i-esimo com a semente `seed + i`, como o `:sim`) e resume. Para ao passar de [maxMillis]. */
internal fun runSimulation(req: SimRequest, base: MatchConfig, maxMillis: Long = SimLimits.MAX_MILLIS): SimSummary {
    val started = System.currentTimeMillis()
    val wins = MutableList(2) { 0 }
    var played = 0
    var finished = 0
    var rounds = 0
    var actions = 0
    var failures = 0
    val samples = mutableListOf<String>()
    var timedOut = false
    for (i in 0 until req.games) {
        if (System.currentTimeMillis() - started > maxMillis) {
            timedOut = true
            break
        }
        val result = MatchRunner(base.copy(seed = req.seed + i)).run()
        played++
        rounds += result.rounds
        actions += result.actions
        if (result.finished) {
            finished++
            wins[result.winnerSide!!]++
        } else if (result.failure != null) {
            failures++
            if (samples.size < 5) samples += "semente ${result.seed}: ${result.failure.kind} ${result.failure.message}"
        }
    }
    return SimSummary(played, finished, wins, rounds, actions, System.currentTimeMillis() - started, failures, samples, timedOut)
}

fun Application.internalSimModule() {
    val busy = AtomicBoolean(false)
    routing {
        post("/internal/sim") {
            val req = try {
                call.receive<SimRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, SimError("corpo invalido"))
            }
            val base = parseSimRequest(req).getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, SimError(it.message ?: "pedido invalido"))
            }
            // Uma simulacao por vez: protege o pod (CPU/memoria limitados).
            if (!busy.compareAndSet(false, true)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, SimError("ja ha uma simulacao em andamento"))
            }
            try {
                call.respond(withContext(Dispatchers.Default) { runSimulation(req, base) })
            } finally {
                busy.set(false)
            }
        }
    }
}
