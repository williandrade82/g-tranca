package com.gtranca.online.game

import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class InternalSimTest {
    @Test
    fun `internal sim roda os jogos e resume sem falhas`() = testApplication {
        application { gameModule() }
        val client = createClient { install(ContentNegotiation) { json() } }
        val response = client.post("/internal/sim") {
            contentType(ContentType.Application.Json)
            setBody(SimRequest(games = 5, seed = 42, mode = "duplas"))
        }
        response.status shouldBe HttpStatusCode.OK
        val s = response.body<SimSummary>()
        s.games shouldBe 5
        s.finished shouldBe 5
        s.failures shouldBe 0
        s.winsBySide.sum() shouldBe 5
    }

    @Test
    fun `internal sim recusa limite excedido, modo invalido e regras invalidas`() = testApplication {
        application { gameModule() }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun status(r: SimRequest) = client.post("/internal/sim") {
            contentType(ContentType.Application.Json)
            setBody(r)
        }.status
        status(SimRequest(games = SimLimits.MAX_GAMES + 1)) shouldBe HttpStatusCode.BadRequest
        status(SimRequest(games = 0)) shouldBe HttpStatusCode.BadRequest
        status(SimRequest(mode = "trio")) shouldBe HttpStatusCode.BadRequest
        status(SimRequest(difficulty = "genio")) shouldBe HttpStatusCode.BadRequest
        status(SimRequest(rulesJson = "{nao e json")) shouldBe HttpStatusCode.BadRequest
        status(SimRequest(games = 1, rulesJson = """{"id":"paulista","groupRanks":"FACE_AND_ACE"}""")) shouldBe HttpStatusCode.OK
    }

    @Test
    fun `timeout interrompe a simulacao e avisa`() {
        val req = SimRequest(games = 50)
        val s = runSimulation(req, parseSimRequest(req).getOrThrow(), maxMillis = -1)
        s.timedOut shouldBe true
        s.games shouldBe 0
    }
}
