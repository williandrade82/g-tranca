package com.gtranca.online.game

import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.ServerInfo
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `18_8 health responde UP e info traz a versao do protocolo`() = testApplication {
        application { gameModule() }
        val client = createClient { install(ContentNegotiation) { json() } }

        val health = client.get("/health")
        health.status shouldBe HttpStatusCode.OK
        health.body<Health>() shouldBe Health("UP")

        client.get("/info").body<ServerInfo>() shouldBe ServerInfo("game-service", ONLINE_PROTOCOL_VERSION)
    }
}
