package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.EmailLoginRequest
import com.gtranca.online.contracts.EmailRegisterRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ResetPasswordRequest
import com.gtranca.online.contracts.ResetPasswordResponse
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** §18.1 (mudanca de 02/11/2026): entrar com e-mail e senha, no lugar do Google. */
class LobbyEmailLoginTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_800_000_000_000L

    // bcrypt real e lento de proposito; custo baixo so nos testes.
    private fun deps(
        walletLinker: AccountWalletLinker? = { _, _ -> false },
        loginAttemptLimit: Int = 5,
        loginLockoutMinutes: Long = 15L,
    ) = LobbyDeps(
        clock = { now },
        emailPasswordCost = 4,
        walletLinker = walletLinker,
        loginAttemptLimit = loginAttemptLimit,
        loginLockoutMinutes = loginLockoutMinutes,
        guestCreationPerMinute = 1_000,
        ipLimitPerMinute = 1_000,
    )

    private suspend fun HttpClient.register(body: EmailRegisterRequest): HttpResponse = post("/api/lobby/accounts/email/register") {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
        setBody(json.encodeToString(EmailRegisterRequest.serializer(), body))
    }

    private suspend fun HttpClient.login(body: EmailLoginRequest): HttpResponse = post("/api/lobby/accounts/email/login") {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
        setBody(json.encodeToString(EmailLoginRequest.serializer(), body))
    }

    @Test
    fun `18_1 cadastro cria a conta e entrar de novo com a mesma senha funciona`() = testApplication {
        application { lobbyModule(deps()) }
        val r = client.register(EmailRegisterRequest("Ana@Exemplo.com", "segredo123", "Ana"))
        r.status.value shouldBe 201
        val acc = json.decodeFromString<AccountLogin>(r.bodyAsText())
        acc.kind shouldBe AccountKind.EMAIL
        acc.name shouldBe "Ana"
        acc.id.startsWith("E_") shouldBe true

        val login = client.login(EmailLoginRequest("ana@exemplo.com", "segredo123"))
        login.status.value shouldBe 200
        val loggedIn = json.decodeFromString<AccountLogin>(login.bodyAsText())
        loggedIn.id shouldBe acc.id
        loggedIn.name shouldBe "Ana"
    }

    @Test
    fun `18_1 cadastro com e-mail ja usado e recusado, mesmo com letras diferentes`() = testApplication {
        application { lobbyModule(deps()) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana")).status.value shouldBe 201
        val r = client.register(EmailRegisterRequest("ANA@EXEMPLO.COM", "outrasenha", "Outra"))
        r.status.value shouldBe 409
        json.decodeFromString<ApiError>(r.bodyAsText()).code shouldBe ErrorCode.EMAIL_JA_CADASTRADO
    }

    @Test
    fun `18_1 senha com menos de 6 caracteres e recusada no cadastro`() = testApplication {
        application { lobbyModule(deps()) }
        val r = client.register(EmailRegisterRequest("ana@exemplo.com", "abc12", "Ana"))
        r.status.value shouldBe 400
        json.decodeFromString<ApiError>(r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_1 e-mail invalido e recusado no cadastro`() = testApplication {
        application { lobbyModule(deps()) }
        val r = client.register(EmailRegisterRequest("nao-e-email", "segredo123", "Ana"))
        r.status.value shouldBe 400
        json.decodeFromString<ApiError>(r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_1 login com senha errada ou e-mail inexistente da o mesmo erro`() = testApplication {
        application { lobbyModule(deps()) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana"))
        val senhaErrada = client.login(EmailLoginRequest("ana@exemplo.com", "senha-errada"))
        senhaErrada.status.value shouldBe 401
        json.decodeFromString<ApiError>(senhaErrada.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO

        val semConta = client.login(EmailLoginRequest("ninguem@exemplo.com", "segredo123"))
        semConta.status.value shouldBe 401
        json.decodeFromString<ApiError>(semConta.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO
    }

    @Test
    fun `18_1 forca bruta bloqueia o e-mail depois de N erros e libera depois da janela`() = testApplication {
        val d = deps(loginAttemptLimit = 3, loginLockoutMinutes = 1L)
        application { lobbyModule(d) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana"))
        repeat(3) {
            client.login(EmailLoginRequest("ana@exemplo.com", "senha-errada")).status.value shouldBe 401
        }
        // a 4a tentativa, mesmo com a senha certa, e recusada pelo bloqueio (nao pela senha)
        val bloqueado = client.login(EmailLoginRequest("ana@exemplo.com", "segredo123"))
        bloqueado.status.value shouldBe 429
        json.decodeFromString<ApiError>(bloqueado.bodyAsText()).code shouldBe ErrorCode.LOGIN_BLOQUEADO

        now += 61_000L // passa da janela de bloqueio (1 min)
        val depois = client.login(EmailLoginRequest("ana@exemplo.com", "segredo123"))
        depois.status.value shouldBe 200
    }

    @Test
    fun `18_1 login certo zera a contagem de erros`() = testApplication {
        val d = deps(loginAttemptLimit = 3, loginLockoutMinutes = 1L)
        application { lobbyModule(d) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana"))
        repeat(2) { client.login(EmailLoginRequest("ana@exemplo.com", "senha-errada")).status.value shouldBe 401 }
        client.login(EmailLoginRequest("ana@exemplo.com", "segredo123")).status.value shouldBe 200
        repeat(2) { client.login(EmailLoginRequest("ana@exemplo.com", "senha-errada")).status.value shouldBe 401 }
        // ainda nao bloqueou: a contagem foi zerada pelo login certo acima
        client.login(EmailLoginRequest("ana@exemplo.com", "segredo123")).status.value shouldBe 200
    }

    @Test
    fun `18_5 cadastro com o token do convidado migra a carteira e o login repetido nao migra de novo`() = testApplication {
        val calls = mutableListOf<Pair<String, String?>>()
        val adopted = mutableSetOf<String>()
        val d = deps(walletLinker = { acc, guest -> calls += acc to guest; guest != null && adopted.add(acc) })
        application { lobbyModule(d) }
        val guest = json.decodeFromString<GuestIdentity>(
            client.post("/api/lobby/guests") {
                header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
                setBody(json.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest("Ana")))
            }.bodyAsText(),
        )
        val r = client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana", guest.token))
        val acc = json.decodeFromString<AccountLogin>(r.bodyAsText())
        acc.walletFromGuest shouldBe true
        calls.single() shouldBe (acc.id to guest.id)

        val again = json.decodeFromString<AccountLogin>(client.login(EmailLoginRequest("ana@exemplo.com", "segredo123", guest.token)).bodyAsText())
        again.walletFromGuest shouldBe false
    }

    @Test
    fun `18_1 economia fora do ar no cadastro responde 503, mas a conta ja existe e entrar depois recupera a carteira`() = testApplication {
        var falhouUmaVez = false
        val d = deps(
            walletLinker = { _, _ ->
                if (!falhouUmaVez) {
                    falhouUmaVez = true
                    throw EconomyGatewayException("fora")
                }
                true
            },
        )
        application { lobbyModule(d) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana")).status.value shouldBe 503
        // a senha ja foi gravada: repetir o cadastro e recusado por e-mail duplicado (prova de que a conta existe)...
        client.register(EmailRegisterRequest("ana@exemplo.com", "outrasenha", "Ana")).status.value shouldBe 409
        // ...e entrar com a senha escolhida funciona e, com a economia de volta, migra a carteira que faltou no cadastro.
        val login = client.login(EmailLoginRequest("ana@exemplo.com", "segredo123"))
        login.status.value shouldBe 200
        json.decodeFromString<AccountLogin>(login.bodyAsText()).walletFromGuest shouldBe true
    }

    @Test
    fun `18_1 rota interna de reset de senha gera senha nova, invalida a antiga e exige o segredo`() = testApplication {
        val d = LobbyDeps(clock = { now }, emailPasswordCost = 4, walletLinker = { _, _ -> false }, internalSecret = "s3gr3d0", guestCreationPerMinute = 1_000, ipLimitPerMinute = 1_000)
        application { lobbyModule(d) }
        client.register(EmailRegisterRequest("ana@exemplo.com", "segredo123", "Ana"))

        // sem segredo configurado no servidor: 503 (testado num app separado, sem internalSecret)
        testApplication {
            application { lobbyModule(LobbyDeps(clock = { now }, emailPasswordCost = 4)) }
            client.post("/internal/lobby/accounts/reset-password") {
                setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ana@exemplo.com")))
            }.status.value shouldBe 503
        }

        // segredo errado: 401
        client.post("/internal/lobby/accounts/reset-password") {
            header(INTERNAL_SECRET_HEADER, "errado")
            setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ana@exemplo.com")))
        }.status.value shouldBe 401

        // conta inexistente: 404
        val inexistente = client.post("/internal/lobby/accounts/reset-password") {
            header(INTERNAL_SECRET_HEADER, "s3gr3d0")
            setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ninguem@exemplo.com")))
        }
        inexistente.status.value shouldBe 404

        // reset de verdade: senha antiga para de funcionar, a nova funciona
        val r1 = client.post("/internal/lobby/accounts/reset-password") {
            header(INTERNAL_SECRET_HEADER, "s3gr3d0")
            setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ana@exemplo.com")))
        }
        r1.status.value shouldBe 200
        val temp1 = json.decodeFromString<ResetPasswordResponse>(r1.bodyAsText()).temporaryPassword
        client.login(EmailLoginRequest("ana@exemplo.com", "segredo123")).status.value shouldBe 401
        client.login(EmailLoginRequest("ana@exemplo.com", temp1)).status.value shouldBe 200

        // segundo reset invalida o primeiro
        val r2 = client.post("/internal/lobby/accounts/reset-password") {
            header(INTERNAL_SECRET_HEADER, "s3gr3d0")
            setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ana@exemplo.com")))
        }
        val temp2 = json.decodeFromString<ResetPasswordResponse>(r2.bodyAsText()).temporaryPassword
        client.login(EmailLoginRequest("ana@exemplo.com", temp1)).status.value shouldBe 401
        client.login(EmailLoginRequest("ana@exemplo.com", temp2)).status.value shouldBe 200
    }
}
