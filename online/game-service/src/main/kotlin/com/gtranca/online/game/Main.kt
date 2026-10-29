package com.gtranca.online.game

import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ServerInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

const val SERVICE_NAME = "game-service"

/** Configuracao por variaveis de ambiente. */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val deps = GameDeps(GameConfig.fromEnv())
    if (deps.config.internalSecret == null) {
        System.err.println("AVISO: INTERNAL_SECRET nao definido; as rotas /internal/* e o canal da partida ficam FECHADOS.")
    }
    embeddedServer(Netty, port = port, host = "0.0.0.0") { gameModule(deps) }.start(wait = true)
}

/** Dependencias do servico (trocaveis nos testes). */
class GameDeps(
    val config: GameConfig = GameConfig(),
    val registry: MatchRegistry = MatchRegistry(config),
    val resolver: GuestResolver = HttpGuestResolver(config.lobbyUrl, config.internalSecret),
)

fun Application.gameModule(deps: GameDeps = GameDeps()) {
    install(ContentNegotiation) { json() }
    monitor.subscribe(ApplicationStopping) { deps.registry.close() }
    routing {
        get("/health") { call.respond(Health()) }
        get("/info") { call.respond(ServerInfo(SERVICE_NAME)) }
    }
    internalModule(deps) // /internal/*: segredo servico a servico; NAO exposto pelo ingress
    playerChannelModule(deps) // /ws/match/{id}
}
