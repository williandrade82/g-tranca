package com.gtranca.online.economy

import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.EconomyStateDto
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** §18.6: POST /economy/{guestId}/migrate, a rota publica que traz a carteira local do aparelho para a conta. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyMigrationHttpTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val key = "chave-teste"
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = "segredo-interno"
    private val tokens = mapOf("tok-E_a" to "E_a", "tok-conv1" to "conv1")

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun app(block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            economyModule(
                EconomyDeps(
                    EconomyStore(db, clock = { now }), key, clock = { now }, internalSecret = secret,
                    guests = GuestResolver { t -> tokens[t] },
                ),
            )
        }
        client.block()
    }

    private suspend fun HttpClient.signedPost(path: String, body: String, token: String?) = post(path) {
        header(AppSignature.TIME_HEADER, now.toString())
        header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(key, "POST", path, now, token))
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.state() = json.decodeFromString<EconomyStateDto>(bodyAsText())
    private suspend fun HttpResponse.error() = json.decodeFromString<EconomyError>(bodyAsText())

    @Test
    fun `18_6 migra a carteira local pela rota publica, e repetir nao soma de novo`() = app {
        val first = signedPost("/economy/E_a/migrate", """{"coins":500,"inventory":{"b1":3}}""", "tok-E_a")
        first.status shouldBe HttpStatusCode.OK
        val s = first.state()
        s.balance shouldBe 1_500
        s.inventory["b1"] shouldBe 8
        s.migratedLocal shouldBe true

        val again = signedPost("/economy/E_a/migrate", """{"coins":999,"inventory":{"b1":50}}""", "tok-E_a")
        again.status shouldBe HttpStatusCode.OK
        again.state() shouldBe s
    }

    @Test
    fun `18_6 convidado recebe CONTA_NAO_ELEGIVEL`() = app {
        val r = signedPost("/economy/conv1/migrate", """{"coins":100,"inventory":{}}""", "tok-conv1")
        r.status shouldBe HttpStatusCode.Forbidden
        r.error().code shouldBe EconomyErrorCode.CONTA_NAO_ELEGIVEL
    }

    @Test
    fun `18_6 so o proprio dono da conta pode migrar`() = app {
        signedPost("/economy/E_a/migrate", """{"coins":100,"inventory":{}}""", token = null).status shouldBe HttpStatusCode.Unauthorized
        signedPost("/economy/E_a/migrate", """{"coins":100,"inventory":{}}""", token = "tok-conv1").status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `18_6 saldo negativo ou absurdo e recusado com 400, e nao migra nada`() = app {
        signedPost("/economy/E_a/migrate", """{"coins":-1,"inventory":{}}""", "tok-E_a").status shouldBe HttpStatusCode.BadRequest
        signedPost("/economy/E_a/migrate", """{"coins":9223372036854775807,"inventory":{}}""", "tok-E_a").status shouldBe HttpStatusCode.BadRequest
        signedPost("/economy/E_a/migrate", """{"coins":0,"inventory":{"b1":-1}}""", "tok-E_a").status shouldBe HttpStatusCode.BadRequest
        val r = signedPost("/economy/E_a/migrate", """{"coins":1,"inventory":{}}""", "tok-E_a")
        r.status shouldBe HttpStatusCode.OK
        r.state().balance shouldBe 1_001 // nenhuma das tentativas recusadas creditou nada antes desta
    }
}
