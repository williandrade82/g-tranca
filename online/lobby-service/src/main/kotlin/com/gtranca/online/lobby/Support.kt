package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.LobbyLimits
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Convidados anonimos: cache em memoria, com copia no banco quando ha [persistence] (plano 2.4). O token e opaco (32 bytes aleatorios); so o hash SHA-256 fica guardado,
 * entao nem um despejo de memoria/log revela tokens validos. Expiram por inatividade.
 */
class GuestRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 24 * 3_600_000L,
    private val maxGuests: Int = 50_000,
    /** Copia duravel (plano 2.4). `null` = so memoria (testes e desenvolvimento). */
    private val persistence: GuestPersistence? = null,
) {
    private class Entry(val guest: Guest, var lastUsedMs: Long, var persistedMs: Long = lastUsedMs)

    private val byHash = HashMap<String, Entry>()
    private val secure = SecureRandom()

    private class Photo(val base64: String, val version: Int)

    private val photos = HashMap<String, Photo>()
    private var photoVersions = 0

    init {
        persistence?.let { p ->
            p.loadGuests().forEach { byHash[it.tokenHash] = Entry(Guest(it.id, it.name), it.lastUsedMs) }
            p.loadPhotos().forEach {
                photos[it.guestId] = Photo(it.base64, it.version)
                photoVersions = maxOf(photoVersions, it.version)
            }
        }
    }

    @Synchronized
    fun create(rawName: String): GuestIdentity = issue(UUID.randomUUID().toString(), sanitizeName(rawName))

    /**
     * Sessao de uma conta (Google, §18.1) no mesmo esquema do convidado: token opaco, so o hash guardado, expira por
     * inatividade (o app entra de novo com o Google). Cada login gera um token novo; os antigos seguem ate expirar.
     */
    @Synchronized
    fun createSession(accountId: String, name: String): GuestIdentity = issue(accountId, name)

    private fun issue(id: String, name: String): GuestIdentity {
        sweep()
        if (byHash.size >= maxGuests) {
            lobbyError(ErrorCode.LIMITE_DE_TAXA, "Servidor ocupado. Tente de novo em instantes.", 503)
        }
        val bytes = ByteArray(32).also(secure::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val guest = Guest(id, name)
        val now = clock()
        persistence?.saveGuest(GuestPersistence.StoredGuest(hash(token), guest.id, guest.name, now))
        byHash[hash(token)] = Entry(guest, now)
        return GuestIdentity(guest.id, guest.name, token)
    }

    @Synchronized
    fun authenticate(token: String): Guest? {
        val entry = byHash[hash(token)] ?: return null
        val now = clock()
        if (now - entry.lastUsedMs > ttlMs) {
            byHash.remove(hash(token))
            persistence?.deleteGuests(listOf(hash(token)))
            return null
        }
        entry.lastUsedMs = now
        // Grava o ultimo uso no maximo uma vez por minuto (a expiracao e de horas; nao precisa de cada chamada).
        val p = persistence
        if (p != null && now - entry.persistedMs >= 60_000L) {
            runCatching { p.touch(hash(token), now) }.onSuccess { entry.persistedMs = now }
        }
        return entry.guest
    }

    /** Guarda (ou tira, com `null`) a foto do convidado (§18.2). */
    @Synchronized
    fun setPhoto(guestId: String, base64: String?) {
        if (base64 == null) {
            persistence?.deletePhotos(listOf(guestId))
            photos.remove(guestId)
            return
        }
        val clean = base64.trim()
        if (clean.isEmpty() || clean.length > LobbyLimits.MAX_PHOTO_BASE64 || !isJpegBase64(clean)) {
            lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Foto inválida ou grande demais.", 400)
        }
        if (photos[guestId]?.base64 == clean) return
        val photo = Photo(clean, ++photoVersions)
        persistence?.savePhoto(GuestPersistence.StoredPhoto(guestId, photo.base64, photo.version))
        photos[guestId] = photo
    }

    @Synchronized
    fun photo(guestId: String): String? = photos[guestId]?.base64

    /** Versao da foto (chave de cache do app), ou `null` sem foto. */
    @Synchronized
    fun photoVersion(guestId: String): Int? = photos[guestId]?.version

    private fun isJpegBase64(text: String): Boolean = runCatching {
        val bytes = Base64.getDecoder().decode(text)
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }.getOrDefault(false)

    /** Convidados que usaram o token nos ultimos [windowMs] (jogadores online, §18.2). */
    @Synchronized
    fun activeIds(windowMs: Long): Set<String> {
        val now = clock()
        return byHash.values.filter { now - it.lastUsedMs <= windowMs }.map { it.guest.id }.toSet()
    }

    private fun sweep() {
        val now = clock()
        val expired = byHash.filterValues { now - it.lastUsedMs > ttlMs }.keys
        val alive = byHash.filterKeys { it !in expired }.values.map { it.guest.id }.toSet()
        // A foto de uma conta (Google ou e-mail) e da conta, nao da sessao: fica mesmo sem token vivo.
        val orphanPhotos = photos.keys.filter { it !in alive && !AccountLimits.isAccountId(it) }
        persistence?.deleteGuests(expired)
        persistence?.deletePhotos(orphanPhotos)
        byHash.keys.removeAll(expired)
        photos.keys.removeAll(orphanPhotos.toSet())
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

/**
 * Limite de tentativas erradas de login por e-mail (§18.1, forca bruta), em memoria, alem do limite de taxa por
 * IP/token (ver [RateLimiter] em Main.kt). Depois de [maxAttempts] erros seguidos para o mesmo e-mail, a chave fica
 * bloqueada por [lockoutMs]; um acerto zera a contagem.
 */
class FailedLoginLimiter(
    private val maxAttempts: Int = 5,
    private val lockoutMs: Long = 15 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var failures: Int = 0, var lockedUntilMs: Long = 0L, var lastFailureMs: Long = 0L)

    private val states = HashMap<String, State>()

    /** Lanca [ErrorCode.LOGIN_BLOQUEADO] se [key] esta bloqueada agora; nao conta como tentativa. */
    @Synchronized
    fun checkAllowed(key: String) {
        val s = states[key] ?: return
        if (clock() < s.lockedUntilMs) {
            lobbyError(ErrorCode.LOGIN_BLOQUEADO, "Muitas tentativas erradas para esse e-mail. Tente de novo em alguns minutos.", 429)
        }
    }

    @Synchronized
    fun recordFailure(key: String) {
        val now = clock()
        // So descarta chaves sem erro recente (nao as travadas: lockedUntilMs sozinho e sempre "no passado" ate travar).
        if (states.size > 20_000) states.entries.removeIf { now - it.value.lastFailureMs > lockoutMs }
        val s = states.getOrPut(key) { State() }
        if (s.lockedUntilMs != 0L && now >= s.lockedUntilMs) {
            // O bloqueio anterior ja passou: comeca a contagem de novo (senao um unico erro a cada janela travaria para sempre).
            s.failures = 0
            s.lockedUntilMs = 0L
        }
        s.failures++
        s.lastFailureMs = now
        if (s.failures >= maxAttempts) s.lockedUntilMs = now + lockoutMs
    }

    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
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
