package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/**
 * POST /api/lobby/accounts/google (§18.1, plano 2.6): entra com o Google.
 * [idToken] e o ID token obtido no aparelho (Credential Manager). [guestToken] e o token do convidado deste aparelho,
 * se houver: a carteira e o inventario dele passam para a conta se ela ainda nao tem carteira online (§18.5).
 */
@Serializable
data class GoogleLoginRequest(val idToken: String, val guestToken: String? = null)

/** Tipo da conta do jogador. */
@Serializable
enum class AccountKind { CONVIDADO, GOOGLE }

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

object AccountLimits {
    const val MAX_ID_TOKEN = 4096

    /** Prefixo do id das contas Google (o id do convidado e um UUID, sem esse prefixo). */
    const val GOOGLE_ID_PREFIX = "G_"
}
