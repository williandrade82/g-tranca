package com.gtranca.ui.online

import com.gtranca.data.OnlineGuest
import com.gtranca.data.SettingsRepository
import com.gtranca.online.client.LobbyApi
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.LobbyLimits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conta online da fase 1 (§18.1): convidado provisório no servidor, criado com o nome do Perfil e guardado só no aparelho
 * (a fase 2 troca por login Google). O servidor do lobby guarda convidados em memória: se ele reiniciou e o token deixou
 * de valer, [call] cria um convidado novo e repete a chamada uma vez.
 *
 * @param displayName nome do Perfil (já com o texto padrão para "sem nome"); cortado em [LobbyLimits.MAX_NAME].
 */
class OnlineAccount(
    private val api: LobbyApi,
    private val settings: SettingsRepository,
    private val displayName: suspend () -> String,
) {
    private val mutex = Mutex()

    /** O convidado guardado para o servidor atual, ou um novo (criado e guardado agora). */
    suspend fun guest(): OnlineGuest = mutex.withLock {
        settings.settings.first().onlineGuest ?: create()
    }

    /** Esquece o convidado guardado (ex.: "Sair"). */
    suspend fun forget() = mutex.withLock { settings.setOnlineGuest(null) }

    /** Executa [block] com o convidado; se o servidor não reconhecer o token, recria o convidado e tenta de novo uma vez. */
    suspend fun <T> call(block: suspend (LobbyApi, OnlineGuest) -> T): T {
        val guest = guest()
        return try {
            block(api, guest)
        } catch (e: OnlineException) {
            if (e.kind != OnlineErrorKind.UNAUTHENTICATED && e.code != ErrorCode.NAO_AUTENTICADO) throw e
            val fresh = mutex.withLock {
                // Outro chamador pode ter renovado enquanto esperávamos a trava.
                val current = settings.settings.first().onlineGuest
                if (current != null && current.token != guest.token) current else create()
            }
            block(api, fresh)
        }
    }

    private suspend fun create(): OnlineGuest {
        val name = displayName().trim().take(LobbyLimits.MAX_NAME).ifBlank { "?" }
        val identity = api.createGuest(name)
        return OnlineGuest(identity.id, identity.name, identity.token).also { settings.setOnlineGuest(it) }
    }
}
