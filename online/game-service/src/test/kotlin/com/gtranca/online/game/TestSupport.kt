package com.gtranca.online.game

import com.gtranca.online.contracts.ResolvedGuest

const val TEST_SECRET = "segredo-de-teste"

/** Resolvedor em memoria: token "tok-<id>" -> convidado "<id>". */
class FakeResolver(private val known: Map<String, ResolvedGuest> = emptyMap(), var down: Boolean = false) : GuestResolver {
    override suspend fun resolve(token: String): ResolvedGuest? {
        if (down) throw LobbyUnavailableException("fora do ar")
        return known[token]
    }
}

fun testDeps(
    config: GameConfig = GameConfig(internalSecret = TEST_SECRET, botDelayMs = 0, roundPauseMs = 0),
    resolver: GuestResolver = FakeResolver(),
) = GameDeps(config, MatchRegistry(config), resolver)
