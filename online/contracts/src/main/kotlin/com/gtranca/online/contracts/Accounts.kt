package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/**
 * POST /api/lobby/accounts/google (§18.1, plano 2.6): entra com o Google.
 * [idToken] e o ID token obtido no aparelho (Credential Manager). [guestToken] e o token do convidado deste aparelho,
 * se houver: a carteira e o inventario dele passam para a conta se ela ainda nao tem carteira online (§18.5).
 */
@Serializable
data class GoogleLoginRequest(val idToken: String, val guestToken: String? = null)

/**
 * POST /api/lobby/accounts/email/register (§18.1, mudanca de 02/11/2026): cria a conta com e-mail e senha.
 * [email] precisa ser unico no servidor (sem confirmacao por e-mail nesta fase); [password] com no minimo
 * [AccountLimits.MIN_PASSWORD] caracteres, sem outra exigencia. [name] e opcional: o app manda o nome do Perfil do
 * aparelho; sem ele, o servidor usa o nome do convidado deste aparelho (via [guestToken]) ou "Jogador".
 * [guestToken] como no login Google: a carteira do convidado deste aparelho passa para a conta nova (§18.5).
 */
@Serializable
data class EmailRegisterRequest(val email: String, val password: String, val name: String? = null, val guestToken: String? = null)

/**
 * POST /api/lobby/accounts/email/login (§18.1): entra com e-mail e senha ja cadastrados. [guestToken] como no
 * cadastro: a carteira do convidado deste aparelho passa para a conta se ela ainda nao tem carteira (§18.5).
 */
@Serializable
data class EmailLoginRequest(val email: String, val password: String, val guestToken: String? = null)

/** Tipo da conta do jogador. */
@Serializable
enum class AccountKind { CONVIDADO, GOOGLE, EMAIL }

/**
 * Resposta do login: o [token] e usado como o do convidado (Authorization: Bearer) em todas as rotas do lobby, da
 * economia e da partida. [pictureUrl] e a foto do Google: o app pode baixar, reduzir e enviar por PUT /guests/me/photo.
 * [walletFromGuest] = a carteira do convidado passou para a conta agora.
 */
@Serializable
data class AccountLogin(
    val id: String,
    val name: String,
    val token: String,
    val kind: AccountKind = AccountKind.GOOGLE,
    val pictureUrl: String? = null,
    val walletFromGuest: Boolean = false,
)

/** Interno lobby -> economy: garante a carteira da conta GOOGLE, adotando a do convidado se a conta ainda nao tem (§18.5). */
@Serializable
data class AdoptWalletRequest(val guestId: String? = null)

@Serializable
data class AdoptWalletResponse(val migrated: Boolean, val state: EconomyStateDto)

/**
 * Rota interna (§18.1, protegida pelo segredo servico a servico) que gera uma senha temporaria nova para uma conta
 * de e-mail: quem chama (suporte, a mao) repassa [temporaryPassword] ao jogador por fora; a senha antiga deixa de
 * valer. Sem recuperacao pelo proprio jogador nesta fase.
 */
@Serializable
data class ResetPasswordRequest(val email: String)

@Serializable
data class ResetPasswordResponse(val temporaryPassword: String)

object AccountLimits {
    const val MAX_ID_TOKEN = 4096

    /** Prefixo do id das contas Google (o id do convidado e um UUID, sem esse prefixo). */
    const val GOOGLE_ID_PREFIX = "G_"

    /** Prefixo do id das contas por e-mail e senha (§18.1, mudanca de 02/11/2026). */
    const val EMAIL_ID_PREFIX = "E_"

    /** true se [id] e de uma conta (Google ou e-mail), nunca de um convidado. */
    fun isAccountId(id: String): Boolean = id.startsWith(GOOGLE_ID_PREFIX) || id.startsWith(EMAIL_ID_PREFIX)

    const val MAX_EMAIL = 254

    /** §18.1: minimo de 6 caracteres, sem outra exigencia. O maximo e so para nao mandar algo absurdo ao bcrypt. */
    const val MIN_PASSWORD = 6
    const val MAX_PASSWORD = 128
}
