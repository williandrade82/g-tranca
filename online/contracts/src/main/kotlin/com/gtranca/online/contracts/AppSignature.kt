package com.gtranca.online.contracts

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * §18.2 "So o aplicativo oficial": cada pedido do app (lobby e handshake do WebSocket) leva o horario e uma assinatura
 * HMAC-SHA256, com a chave do aplicativo, de `metodo \n caminho \n horario \n token`. O corpo nao entra (o lobby nao o
 * le duas vezes); quem nao tem a chave nao assina nada, que e o objetivo. A chave vem de fora do repositorio (build do
 * app e Secret do cluster). Quem desmontar o APK obtem a chave: o atestado do Google (Play Integrity) vem com a loja.
 */
object AppSignature {
    const val TIME_HEADER = "X-App-Time"
    const val SIGNATURE_HEADER = "X-App-Signature"

    /** Horario do pedido aceito ate 5 min para mais ou para menos (relogio do celular fora de hora). */
    const val MAX_SKEW_MS = 5 * 60_000L

    fun sign(key: String, method: String, path: String, timeMs: Long, token: String?): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        val text = "${method.uppercase()}\n$path\n$timeMs\n${token.orEmpty()}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.toByteArray()))
    }

    /** `true` se a assinatura confere com a chave e o horario esta dentro da janela. */
    fun verify(key: String, method: String, path: String, time: String?, signature: String?, token: String?, nowMs: Long): Boolean {
        val timeMs = time?.toLongOrNull() ?: return false
        if (signature.isNullOrEmpty() || kotlin.math.abs(nowMs - timeMs) > MAX_SKEW_MS) return false
        val expected = sign(key, method, path, timeMs, token)
        return MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())
    }
}
