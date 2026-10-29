package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/**
 * Versao do protocolo online (§18.8). Sobe quando app e servidor deixam de se entender.
 * 2 (etapa 1.3c): relogio de jogada na vista (`turn`), `endReason`, `reason` em `SeatsChanged` e a mensagem `closed`.
 */
const val ONLINE_PROTOCOL_VERSION: Int = 2

/** Resposta de GET /health. */
@Serializable
data class Health(val status: String = "UP")

/** Resposta de GET /info: quem e o servidor e que protocolo fala. */
@Serializable
data class ServerInfo(
    val service: String,
    val protocolVersion: Int = ONLINE_PROTOCOL_VERSION,
)

/** Resultado da checagem de versao feita pelo app/servidor (§18.8). */
sealed interface VersionCheck {
    data object Compatible : VersionCheck
    data class Incompatible(val message: String) : VersionCheck
}

/** Mensagens em pt-BR de §18.8. */
fun checkProtocolVersion(remote: Int, local: Int = ONLINE_PROTOCOL_VERSION): VersionCheck = when {
    remote == local -> VersionCheck.Compatible
    remote < local -> VersionCheck.Incompatible("Atualize o aplicativo")
    else -> VersionCheck.Incompatible("Servidor em manutenção")
}
