package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Identidade Google ja verificada (§18.1). */
data class GoogleUser(val sub: String, val name: String?, val picture: String?) {
    /** Id estavel da conta, derivado do `sub` (o `sub` em si nao sai do servidor). */
    val accountId: String
        get() = AccountLimits.GOOGLE_ID_PREFIX +
            MessageDigest.getInstance("SHA-256").digest("google:$sub".toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
}

/** ID token recusado (assinatura, emissor, publico ou validade). A mensagem e so para log, sem o token. */
class InvalidIdTokenException(message: String) : RuntimeException(message)

/** Chaves publicas do Google (JWKS) por `kid`. */
fun interface JwksSource {
    /** [forceRefresh] quando o `kid` nao esta no cache (o Google girou as chaves). */
    suspend fun keys(forceRefresh: Boolean): Map<String, PublicKey>
}

/** JWKS do Google por HTTP, com cache pelo `max-age` (padrao 1 h) e no maximo um refresco forcado por minuto. */
class HttpJwksSource(
    private val url: String = "https://www.googleapis.com/oauth2/v3/certs",
    private val clock: () -> Long = System::currentTimeMillis,
) : JwksSource {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 3_000
            }
        }
    }
    private val mutex = Mutex()
    private var cached: Map<String, PublicKey> = emptyMap()
    private var expiresMs = 0L
    private var lastFetchMs = 0L

    override suspend fun keys(forceRefresh: Boolean): Map<String, PublicKey> = mutex.withLock {
        val now = clock()
        val stale = now >= expiresMs
        val canForce = forceRefresh && now - lastFetchMs >= 60_000L
        if (cached.isEmpty() || stale || canForce) {
            try {
                val response = client.get(url)
                if (response.status.value != 200) throw InvalidIdTokenException("jwks respondeu ${response.status.value}")
                cached = parseJwks(response.bodyAsText())
                val maxAge = response.headers["Cache-Control"]?.let { Regex("max-age=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                expiresMs = now + (maxAge ?: 3_600L).coerceIn(60L, 86_400L) * 1000
                lastFetchMs = now
            } catch (e: Exception) {
                if (cached.isEmpty()) throw JwksUnavailableException(e::class.simpleName.orEmpty())
                // Sem rede: segue com as chaves antigas (o Google as mantem validas por dias).
            }
        }
        cached
    }
}

/** Nao foi possivel obter as chaves do Google (sem internet no pod?): login indisponivel, nao "token invalido". */
class JwksUnavailableException(message: String) : RuntimeException(message)

private val jwtJson = Json { ignoreUnknownKeys = true }

/** Converte um JWKS (RSA) em chaves por `kid`. */
fun parseJwks(text: String): Map<String, PublicKey> {
    val factory = KeyFactory.getInstance("RSA")
    val dec = Base64.getUrlDecoder()
    return jwtJson.parseToJsonElement(text).jsonObject["keys"]!!.jsonArray.mapNotNull { el ->
        val k = el.jsonObject
        if (k["kty"]?.jsonPrimitive?.contentOrNull != "RSA") return@mapNotNull null
        val kid = k["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val n = BigInteger(1, dec.decode(k["n"]!!.jsonPrimitive.content))
        val e = BigInteger(1, dec.decode(k["e"]!!.jsonPrimitive.content))
        kid to factory.generatePublic(RSAPublicKeySpec(n, e))
    }.toMap()
}

/**
 * Verifica o ID token do Google (§18.1: o servidor valida a identidade): RS256 com a chave do JWKS pelo `kid`,
 * `iss` do Google, `aud` = [clientId], `exp` no futuro (tolerancia de [skewMs]) e `sub` presente.
 */
class GoogleIdTokenVerifier(
    private val clientId: String,
    private val jwks: JwksSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = 60_000L,
) {
    suspend fun verify(idToken: String): GoogleUser {
        if (idToken.length > AccountLimits.MAX_ID_TOKEN) throw InvalidIdTokenException("grande demais")
        val parts = idToken.split('.')
        if (parts.size != 3) throw InvalidIdTokenException("formato")
        val dec = Base64.getUrlDecoder()
        val header = decodeObject(parts[0])
        val claims = decodeObject(parts[1])
        if (header["alg"]?.jsonPrimitive?.contentOrNull != "RS256") throw InvalidIdTokenException("alg")
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: throw InvalidIdTokenException("kid")
        val key = jwks.keys(false)[kid] ?: jwks.keys(true)[kid] ?: throw InvalidIdTokenException("kid desconhecido")
        val signature = try { dec.decode(parts[2]) } catch (e: IllegalArgumentException) { throw InvalidIdTokenException("assinatura") }
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            runCatching { verify(signature) }.getOrDefault(false)
        }
        if (!ok) throw InvalidIdTokenException("assinatura")
        val iss = claims["iss"]?.jsonPrimitive?.contentOrNull
        if (iss != "https://accounts.google.com" && iss != "accounts.google.com") throw InvalidIdTokenException("iss")
        val audOk = when (val aud = claims["aud"]) {
            null -> false
            is JsonObject -> false
            is kotlinx.serialization.json.JsonArray -> aud.any { it.jsonPrimitive.contentOrNull == clientId }
            else -> aud.jsonPrimitive.contentOrNull == clientId
        }
        if (!audOk) throw InvalidIdTokenException("aud")
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: throw InvalidIdTokenException("exp")
        if (exp * 1000 + skewMs < clock()) throw InvalidIdTokenException("expirado")
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw InvalidIdTokenException("sub")
        return GoogleUser(
            sub = sub,
            name = claims["name"]?.jsonPrimitive?.contentOrNull,
            picture = claims["picture"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") && it.length <= 1024 },
        )
    }

    private fun decodeObject(part: String): JsonObject = try {
        jwtJson.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()).jsonObject
    } catch (e: Exception) {
        throw InvalidIdTokenException("formato")
    }
}
