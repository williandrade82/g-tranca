package com.gtranca.online.lobby

import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.LobbyLimits
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Convidados anonimos em MEMORIA. O token e opaco (32 bytes aleatorios); so o hash SHA-256 fica guardado,
 * entao nem um despejo de memoria/log revela tokens validos. Expiram por inatividade.
 */
class GuestRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 24 * 3_600_000L,
    private val maxGuests: Int = 50_000,
) {
    private class Entry(val guest: Guest, var lastUsedMs: Long)

    private val byHash = HashMap<String, Entry>()
    private val secure = SecureRandom()

    @Synchronized
    fun create(rawName: String): GuestIdentity {
        val name = sanitizeName(rawName)
        sweep()
        if (byHash.size >= maxGuests) {
            lobbyError(ErrorCode.LIMITE_DE_TAXA, "Servidor ocupado. Tente de novo em instantes.", 503)
        }
        val bytes = ByteArray(32).also(secure::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val guest = Guest(UUID.randomUUID().toString(), name)
        byHash[hash(token)] = Entry(guest, clock())
        return GuestIdentity(guest.id, guest.name, token)
    }

    @Synchronized
    fun authenticate(token: String): Guest? {
        val entry = byHash[hash(token)] ?: return null
        val now = clock()
        if (now - entry.lastUsedMs > ttlMs) {
            byHash.remove(hash(token))
            return null
        }
        entry.lastUsedMs = now
        return entry.guest
    }

    private fun sweep() {
        val now = clock()
        byHash.values.removeIf { now - it.lastUsedMs > ttlMs }
    }

    companion object {
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Nome de exibicao: 1 a 24 caracteres, sem controles. */
        fun sanitizeName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty() || name.length > LobbyLimits.MAX_NAME || name.any { it.isISOControl() }) {
                lobbyError(ErrorCode.REQUISICAO_INVALIDA, "O nome deve ter de 1 a ${LobbyLimits.MAX_NAME} caracteres.", 400)
            }
            return name
        }
    }
}

/** Limite de taxa por janela fixa, em memoria. */
class RateLimiter(
    private val limit: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    /** true se a chamada pode seguir. */
    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        if (windows.size > 20_000) windows.values.removeIf { now - it.start >= windowMs }
        val w = windows.getOrPut(key) { Window(now, 0) }
        if (now - w.start >= windowMs) {
            w.start = now
            w.count = 0
        }
        w.count++
        return w.count <= limit
    }
}
