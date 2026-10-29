package com.gtranca.online.game

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.CreateMatchResponse
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest

/** Compara em tempo constante. */
internal fun secretMatches(expected: String, given: String?): Boolean =
    given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

/**
 * Rotas `/internal/...`: so servico a servico. Exigem o segredo compartilhado ([GameConfig.internalSecret], de variavel de
 * ambiente/Secret do Kubernetes); sem segredo configurado ficam FECHADAS (503). O ingress tambem nao as expoe.
 */
fun Application.internalModule(deps: GameDeps) {
    routing {
        route("/internal") {
            install(internalGuard(deps))
            matchRoutes(deps)
            simRoutes()
        }
    }
}

private fun Route.matchRoutes(deps: GameDeps) {
    // lobby -> game: cria a partida (ao anfitriao iniciar). Idempotente por mesa.
    post("/matches") {
        val req = try {
            call.receive<CreateMatchRequest>()
        } catch (e: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError(ErrorCode.REQUISICAO_INVALIDA, "Pedido inválido."))
        }
        try {
            val (session, created) = deps.registry.create(req)
            call.respond(
                if (created) HttpStatusCode.Created else HttpStatusCode.OK,
                CreateMatchResponse(session.tableId),
            )
        } catch (e: MatchCreateException) {
            val code = if (e.status == 503) ErrorCode.PARTIDA_INDISPONIVEL else ErrorCode.REQUISICAO_INVALIDA
            call.respond(HttpStatusCode.fromValue(e.status), ApiError(code, e.message))
        }
    }
}

/** Plugin do escopo da rota `/internal`: responde 503 (sem segredo configurado) ou 401 e nao deixa o pedido seguir. */
private fun internalGuard(deps: GameDeps) = createRouteScopedPlugin("InternalGuard") {
    onCall { call ->
        val expected = deps.config.internalSecret
        when {
            expected == null ->
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.NAO_AUTENTICADO, "Rotas internas desativadas."))
            !secretMatches(expected, call.request.headers[INTERNAL_SECRET_HEADER]) ->
                call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorCode.NAO_AUTENTICADO, "Não autorizado."))
        }
    }
}
