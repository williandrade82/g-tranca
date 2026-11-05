package com.gtranca.online.economy

import com.gtranca.online.contracts.BoosterDebitResponse
import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** §17.3/§18.3 a rota interna que debita os boosters do seletor online (so game-service chama, com o segredo interno). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyBoosterHttpTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = "segredo-interno"

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun app(block: suspend HttpClient.() -> Unit) = testApplication {
        application { economyModule(EconomyDeps(EconomyStore(db), internalSecret = secret)) }
        client.block()
    }

    private suspend fun HttpClient.internal(path: String, body: String) = post(path) {
        header(INTERNAL_SECRET_HEADER, secret)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `17_3 debita os boosters do assento e exige o segredo interno`() = app {
        post("/internal/economy/matches/ABC234/seats/0/boosters") {
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":"g1","boosterIds":["b1","b2"]}""")
        }.status shouldBe HttpStatusCode.Unauthorized

        val r = internal(
            "/internal/economy/matches/ABC234/seats/0/boosters",
            """{"accountId":"g1","boosterIds":["b1","b2"]}""",
        )
        r.status shouldBe HttpStatusCode.OK
        json.decodeFromString<BoosterDebitResponse>(r.bodyAsText()).applied.toSet() shouldBe setOf("b1", "b2")

        val state = get("/internal/economy/accounts/g1") { header(INTERNAL_SECRET_HEADER, secret) }
        json.decodeFromString<EconomyStateDto>(state.bodyAsText()).inventory shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 5)

        // Repetir (rede falhou depois de debitar) nao debita de novo.
        internal("/internal/economy/matches/ABC234/seats/0/boosters", """{"accountId":"g1","boosterIds":["b1","b2"]}""")
        val again = get("/internal/economy/accounts/g1") { header(INTERNAL_SECRET_HEADER, secret) }
        json.decodeFromString<EconomyStateDto>(again.bodyAsText()).inventory shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 5)
    }

    @Test
    fun `17_3 partida e assento invalidos, e boosters demais, sao recusados com 400`() = app {
        internal("/internal/economy/matches//seats/0/boosters", """{"accountId":"g1","boosterIds":["b1"]}""")
            .status shouldBe HttpStatusCode.NotFound // id vazio nem bate a rota
        internal("/internal/economy/matches/ABC234/seats/9/boosters", """{"accountId":"g1","boosterIds":["b1"]}""")
            .status shouldBe HttpStatusCode.BadRequest
        internal(
            "/internal/economy/matches/ABC234/seats/0/boosters",
            """{"accountId":"g1","boosterIds":["b1","b2","b3","b1","b2"]}""",
        ).status shouldBe HttpStatusCode.BadRequest
    }
}
