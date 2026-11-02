package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/**
 * Versao do protocolo online (§18.8). Sobe quando app e servidor deixam de se entender.
 * 2 (etapa 1.3c): relogio de jogada na vista (`turn`), `endReason`, `reason` em `SeatsChanged` e a mensagem `closed`.
 * 3 (30/10/2026): mesas mantidas pelo servidor (§18.2): lista de mesas, anfitriao = 1o a entrar, Entregar a mesa,
 * 15 minutos de jogada e assinatura do aplicativo.
 * 4 (etapa 2.5, §17.3/§18.3): seletor de boosters online no inicio do jogo (`boosterPrompt`, `selectBoosters`,
 * `boostersConfirmed`, `boosterConfirmFailed`), `choosingBoosters` em `SeatInfo` e `BOOSTERS_CHOSEN` em `SeatChange`.
 * game-service e o APK precisam subir juntos (app antigo nao entende as mensagens novas; servidor antigo nao as envia).
 */
const val ONLINE_PROTOCOL_VERSION: Int = 4

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
