package com.gtranca.online.client

import com.gtranca.online.contracts.TableId

/**
 * Endereço do servidor online (campo temporário do Perfil, §18.1): `host` ou `host:porta`, sem esquema.
 * Texto puro (`http`/`ws`) só quando [cleartext] (build de teste); o release usa sempre `https`/`wss`.
 * Nenhum endereço fixo: o valor vem sempre do que o jogador digitou.
 */
class OnlineEndpoint private constructor(val hostPort: String, val cleartext: Boolean) {
    val httpBase: String get() = (if (cleartext) "http://" else "https://") + hostPort
    val wsBase: String get() = (if (cleartext) "ws://" else "wss://") + hostPort

    /** Link do convite (§18.2), o mesmo do QR Code. */
    fun inviteLink(tableId: String): String = "$httpBase/m/${TableId.normalize(tableId) ?: tableId}"

    override fun equals(other: Any?) = other is OnlineEndpoint && other.hostPort == hostPort && other.cleartext == cleartext
    override fun hashCode() = hostPort.hashCode() * 31 + cleartext.hashCode()
    override fun toString() = httpBase

    companion object {
        private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$""")

        /**
         * Normaliza o que o jogador digitou: tira espaços, esquema (`http://`, `https://`) e barras finais; aceita `:porta`
         * (1..65535). Devolve `null` se vazio ou inválido (ex.: caminho, espaços no meio).
         */
        fun parse(input: String, cleartext: Boolean): OnlineEndpoint? {
            var text = input.trim()
            text = text.removePrefixIgnoreCase("https://").removePrefixIgnoreCase("http://").trimEnd('/')
            if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }) return null
            val colon = text.lastIndexOf(':')
            val host = if (colon >= 0) text.substring(0, colon) else text
            if (colon >= 0) {
                val port = text.substring(colon + 1).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
            }
            if (!HOST.matches(host)) return null
            return OnlineEndpoint(text.lowercase(), cleartext)
        }

        private fun String.removePrefixIgnoreCase(prefix: String) =
            if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
    }
}
