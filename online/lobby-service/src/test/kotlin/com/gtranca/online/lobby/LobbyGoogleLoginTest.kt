package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GoogleLoginRequest
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** §18.1 login Google (plano 2.6) com chaves de teste (JWKS falso). */
class LobbyGoogleLoginTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clientId = "765029986627-teste.apps.googleusercontent.com"
    private val now = 1_800_000_000_000L
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun unsigned(bytes: ByteArray) = bytes.dropWhile { it == 0.toByte() }.toByteArray()

    private val jwksText = buildJsonObject {
        put(
            "keys",
            buildJsonArray {
                add(
                    buildJsonObject {
                        val pub = key.public as RSAPublicKey
                        put("kty", "RSA")
                        put("kid", "k1")
                        put("alg", "RS256")
                        put("n", b64.encodeToString(unsigned(pub.modulus.toByteArray())))
                        put("e", b64.encodeToString(unsigned(pub.publicExponent.toByteArray())))
                    },
                )
            },
        )
    }.toString()
    private val verifier = GoogleIdTokenVerifier(clientId, { parseJwks(jwksText) }, clock = { now })

    private fun token(
        aud: String = clientId,
        iss: String = "https://accounts.google.com",
        expSec: Long = now / 1000 + 600,
        sub: String = "1234567890",
        signer: PrivateKey = key.private,
        kid: String = "k1",
    ): String {
        val header = b64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"$kid\",\"typ\":\"JWT\"}".toByteArray())
        val claims = b64.encodeToString(
            ("{\"iss\":\"$iss\",\"aud\":\"$aud\",\"sub\":\"$sub\",\"exp\":$expSec,\"name\":\"Ana Souza\"," +
                "\"picture\":\"https://lh3.googleusercontent.com/a/x\"}").toByteArray(),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(signer)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "$header.$claims.${b64.encodeToString(sig)}"
    }

    @Test
    fun `18_1 token valido e aceito e da um id estavel derivado do sub`() = runBlocking {
        val u = verifier.verify(token())
        u.name shouldBe "Ana Souza"
        u.accountId shouldStartWith "G_"
        verifier.verify(token()).accountId shouldBe u.accountId
        (verifier.verify(token(sub = "outro")).accountId == u.accountId) shouldBe false
    }

    @Test
    fun `18_1 aud errada, emissor errado, expirado, assinatura invalida e kid desconhecido sao recusados`() = runBlocking {
        listOf(
            token(aud = "outro-app"),
            token(iss = "https://evil.example"),
            token(expSec = now / 1000 - 3600),
            token(signer = otherKey.private),
            token(kid = "k9"),
            token().dropLast(6) + "AAAAAA",
            "lixo",
        ).forEach { t -> shouldThrow<InvalidIdTokenException> { verifier.verify(t) } }
    }

    private suspend fun HttpClient.login(body: GoogleLoginRequest): HttpResponse = post("/api/lobby/accounts/google") {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
        setBody(json.encodeToString(GoogleLoginRequest.serializer(), body))
    }

    @Test
    fun `18_5 login com o token do convidado vincula a carteira uma vez e o token da conta vale no lobby`() {
        val calls = mutableListOf<Pair<String, String?>>()
        val adopted = mutableSetOf<String>()
        val deps = LobbyDeps(
            clock = { now },
            google = verifier,
            walletLinker = { acc, guest ->
                calls += acc to guest
                guest != null && adopted.add(acc)
            },
        )
        val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(-1, -40, -1, 0))
        testApplication {
            application { lobbyModule(deps) }
            val guest = json.decodeFromString<GuestIdentity>(
                client.post("/api/lobby/guests") {
                    header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
                    setBody(json.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest("Ana")))
                }.bodyAsText(),
            )
            val first = client.login(GoogleLoginRequest(token(), guest.token))
            first.status.value shouldBe 200
            val acc = json.decodeFromString<AccountLogin>(first.bodyAsText())
            acc.kind shouldBe AccountKind.GOOGLE
            acc.walletFromGuest shouldBe true
            acc.name shouldBe "Ana Souza"
            acc.pictureUrl shouldBe "https://lh3.googleusercontent.com/a/x"
            calls.single() shouldBe (acc.id to guest.id)
            // vinculo repetido: mesma conta, nada migra de novo
            val again = json.decodeFromString<AccountLogin>(client.login(GoogleLoginRequest(token(), guest.token)).bodyAsText())
            again.id shouldBe acc.id
            again.walletFromGuest shouldBe false
            // o token da conta vale como o do convidado (foto da conta)
            client.put("/api/lobby/guests/me/photo") {
                header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
                header(HttpHeaders.Authorization, "Bearer ${acc.token}")
                setBody("{\"photoBase64\":\"$jpeg\"}")
            }.status.value shouldBe 200
            deps.guests.photo(acc.id) shouldBe jpeg
        }
    }

    @Test
    fun `18_1 token recusado responde 401, sem verificador 503 e economia fora do ar 503`() {
        testApplication {
            application { lobbyModule(LobbyDeps(clock = { now }, google = verifier, walletLinker = { _, _ -> false })) }
            val r = client.login(GoogleLoginRequest(token(aud = "x")))
            r.status.value shouldBe 401
            json.decodeFromString<ApiError>(r.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO
        }
        testApplication {
            application { lobbyModule(LobbyDeps(clock = { now })) }
            json.decodeFromString<ApiError>(client.login(GoogleLoginRequest(token())).bodyAsText()).code shouldBe ErrorCode.LOGIN_INDISPONIVEL
        }
        testApplication {
            application {
                lobbyModule(LobbyDeps(clock = { now }, google = verifier, walletLinker = { _, _ -> throw EconomyGatewayException("fora") }))
            }
            client.login(GoogleLoginRequest(token())).status.value shouldBe 503
        }
    }
}
