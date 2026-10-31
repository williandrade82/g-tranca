package com.gtranca.online.economy

import com.gtranca.engine.Shop
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.BetHoldResponse
import com.gtranca.online.contracts.BetSettleResponse
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.EconomyStateDto
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

/** §18.5 rotas publicas da economia, com a assinatura do app (§18.2). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyHttpTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val key = "chave-teste"
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = "segredo-interno"
    private val tokens = mapOf("tok-g1" to "g1", "tok-g2" to "g2", "tok-zz" to "zz")
    private var lobbyDown = false

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
                    EconomyStore(db), key, clock = { now }, internalSecret = secret,
                    guests = GuestResolver { t -> if (lobbyDown) throw LobbyUnavailableException("fora") else tokens[t] },
                ),
            )
        }
        client.block()
    }

    /** Token do dono do caminho (/economy/<id>/...) por padrao. */
    private fun tokenFor(path: String) = "tok-" + path.removePrefix("/economy/").substringBefore('/')

    private suspend fun HttpClient.signedGet(path: String, token: String? = tokenFor(path)) = get(path) {
        header(AppSignature.TIME_HEADER, now.toString())
        header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(key, "GET", path, now, token))
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpClient.signedPost(path: String, body: String, token: String? = tokenFor(path)) = post(path) {
        header(AppSignature.TIME_HEADER, now.toString())
        header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(key, "POST", path, now, token))
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.state() = json.decodeFromString<EconomyStateDto>(bodyAsText())
    private suspend fun HttpResponse.error() = json.decodeFromString<EconomyError>(bodyAsText())

    @Test
    fun `18_2 assinatura invalida, ausente ou vencida e recusada`() = app {
        get("/economy/g1").status shouldBe HttpStatusCode.Unauthorized
        val bad = get("/economy/g1") {
            header(AppSignature.TIME_HEADER, now.toString())
            header(AppSignature.SIGNATURE_HEADER, AppSignature.sign("outra", "GET", "/economy/g1", now, null))
        }
        bad.status shouldBe HttpStatusCode.Unauthorized
        bad.error().code shouldBe EconomyErrorCode.APP_NAO_RECONHECIDO
        val old = now - AppSignature.MAX_SKEW_MS - 1
        get("/economy/g1") {
            header(AppSignature.TIME_HEADER, old.toString())
            header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(key, "GET", "/economy/g1", old, null))
        }.status shouldBe HttpStatusCode.Unauthorized
        get("/health").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `14_6 estado inicial e ganho diario uma vez por dia`() = app {
        signedGet("/economy/g1").state().balance shouldBe 1_000
        signedPost("/economy/g1/daily", """{"today":100}""").state().balance shouldBe 1_200
        val again = signedPost("/economy/g1/daily", """{"today":100}""")
        again.status shouldBe HttpStatusCode.Conflict
        again.error().code shouldBe EconomyErrorCode.JA_RESGATADO
    }

    @Test
    fun `17_10 compra idempotente pela mesma chave`() = app {
        signedGet("/economy/g1")
        val first = signedPost("/economy/g1/buy", """{"offerIndex":0,"key":"k1"}""")
        first.status shouldBe HttpStatusCode.OK
        val s = first.state()
        s.balance shouldBe 1_000L - Shop.OFFERS[0].price
        signedPost("/economy/g1/buy", """{"offerIndex":0,"key":"k1"}""").state() shouldBe s
    }

    @Test
    fun `17_10 saldo insuficiente 409, conta inexistente 404, oferta inexistente 400`() = app {
        signedPost("/economy/zz/buy", """{"offerIndex":0,"key":"k"}""").status shouldBe HttpStatusCode.NotFound
        signedGet("/economy/g1")
        val price = Shop.OFFERS[0].price
        repeat(1_000 / price) { signedPost("/economy/g1/buy", """{"offerIndex":0,"key":"p$it"}""") }
        val r = signedPost("/economy/g1/buy", """{"offerIndex":0,"key":"fim"}""")
        r.status shouldBe HttpStatusCode.Conflict
        r.error().code shouldBe EconomyErrorCode.SALDO_INSUFICIENTE
        signedPost("/economy/g1/buy", """{"offerIndex":999,"key":"x"}""").status shouldBe HttpStatusCode.BadRequest
        signedPost("/economy/g1/buy", "lixo").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `18_1 rotas da carteira exigem o token do proprio jogador`() = app {
        signedGet("/economy/g1", token = null).status shouldBe HttpStatusCode.Unauthorized
        signedGet("/economy/g1", token = "tok-falso").status shouldBe HttpStatusCode.Unauthorized
        val other = signedGet("/economy/g1", token = "tok-g2")
        other.status shouldBe HttpStatusCode.Forbidden
        other.error().code shouldBe EconomyErrorCode.SEM_PERMISSAO
        signedPost("/economy/g1/daily", """{"today":1}""", token = "tok-g2").status shouldBe HttpStatusCode.Forbidden
        lobbyDown = true
        try {
            signedGet("/economy/g1").status shouldBe HttpStatusCode.ServiceUnavailable
        } finally {
            lobbyDown = false
        }
        signedGet("/economy/g1").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `18_2 rotas internas de aposta exigem o segredo e retem e liquidam uma vez so`() = app {
        post("/internal/economy/bets/b1/hold") {
            contentType(ContentType.Application.Json); setBody("""{"amount":50,"accounts":["g1"]}""")
        }.status shouldBe HttpStatusCode.Unauthorized
        suspend fun internal(path: String, body: String) = post(path) {
            header(INTERNAL_SECRET_HEADER, secret); contentType(ContentType.Application.Json); setBody(body)
        }
        val hold = internal("/internal/economy/bets/b1/hold", """{"amount":50,"accounts":["g1","g2"]}""")
        hold.status shouldBe HttpStatusCode.OK
        json.decodeFromString<BetHoldResponse>(hold.bodyAsText()).held shouldBe listOf("g1", "g2")
        internal("/internal/economy/bets/b1/hold", """{"amount":50,"accounts":["g1","g2"]}""")
        val bal = get("/internal/economy/accounts/g1") { header(INTERNAL_SECRET_HEADER, secret) }
        json.decodeFromString<EconomyStateDto>(bal.bodyAsText()).balance shouldBe 950
        val body = """{"payouts":[{"accountId":"g1","kind":"PREMIO","amount":100},{"accountId":"g2","kind":"PERDA","amount":0}]}"""
        json.decodeFromString<BetSettleResponse>(internal("/internal/economy/bets/b1/settle", body).bodyAsText()) shouldBe BetSettleResponse(2, 0)
        json.decodeFromString<BetSettleResponse>(internal("/internal/economy/bets/b1/settle", body).bodyAsText()) shouldBe BetSettleResponse(0, 2)
        signedGet("/economy/g1").state().balance shouldBe 1_050
        signedGet("/economy/g2").state().balance shouldBe 950
        internal("/internal/economy/bets/b2/settle", body).status shouldBe HttpStatusCode.NotFound
    }
}
