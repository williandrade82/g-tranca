package com.gtranca.online.admin

/**
 * Limite de tentativas erradas de login por e-mail (Sec18.7/Sec18.1, forca bruta), em memoria, alem do limite de
 * taxa por IP ([RateLimiter]). Depois de [maxAttempts] erros seguidos para o mesmo e-mail, a chave fica bloqueada
 * por [lockoutMs]; um acerto zera a contagem. Implementado aqui (mesmo algoritmo do lobby-service), sem depender
 * daquele modulo.
 */
class FailedLoginLimiter(
    private val maxAttempts: Int = 5,
    private val lockoutMs: Long = 15 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var failures: Int = 0, var lockedUntilMs: Long = 0L, var lastFailureMs: Long = 0L)

    private val states = HashMap<String, State>()

    /** Lanca [AdminException] (LOGIN_BLOQUEADO) se [key] esta bloqueada agora; nao conta como tentativa. */
    @Synchronized
    fun checkAllowed(key: String) {
        val s = states[key] ?: return
        if (clock() < s.lockedUntilMs) {
            adminError(com.gtranca.online.contracts.ErrorCode.LOGIN_BLOQUEADO, "Muitas tentativas erradas para esse e-mail. Tente de novo em alguns minutos.", 429)
        }
    }

    @Synchronized
    fun recordFailure(key: String) {
        val now = clock()
        if (states.size > 20_000) states.entries.removeIf { now - it.value.lastFailureMs > lockoutMs }
        val s = states.getOrPut(key) { State() }
        if (s.lockedUntilMs != 0L && now >= s.lockedUntilMs) {
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

/** Limite de taxa por janela fixa, em memoria (mesmo algoritmo do lobby-service, implementado aqui separado). */
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
