package com.gtranca.online.game

import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ServerInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
    embeddedServer(Netty, port = port, host = "0.0.0.0") { gameModule() }.start(wait = true)
}

fun Application.gameModule() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(Health()) }
        get("/info") { call.respond(ServerInfo(SERVICE_NAME)) }
    }
    internalSimModule() // /internal/*: so validacao local, sera removido/fechado
}
