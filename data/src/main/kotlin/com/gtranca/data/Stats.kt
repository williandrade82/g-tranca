package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import java.io.IOException
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.gtranca.engine.Coins
import com.gtranca.engine.ShopOffer
import com.gtranca.engine.Wallet
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resultado de um jogo do ponto de vista do humano. A desistência (§13.1) conta como derrota. */
enum class GameResult { WIN, LOSS, RESIGNATION }

/** Grupo das estatísticas: modo e dificuldade (identificador, ex.: "medio"). */
data class StatsKey(val mode: GameMode, val difficultyId: String)

/** Contagens de um grupo. [losses] não inclui as desistências; [defeats] soma as duas. */
data class StatsLine(val games: Int = 0, val wins: Int = 0, val losses: Int = 0, val resignations: Int = 0) {
    val defeats: Int get() = losses + resignations

    /** Porcentagem de vitórias (0 sem jogos). */
    val winPercent: Int get() = if (games == 0) 0 else (wins * 100 + games / 2) / games
}

data class GameStats(val lines: Map<StatsKey, StatsLine> = emptyMap()) {
    fun line(mode: GameMode, difficultyId: String): StatsLine = lines[StatsKey(mode, difficultyId)] ?: StatsLine()
}

interface StatsRepository {
    val stats: Flow<GameStats>

    /**
     * Registra o fim do jogo [gameId]. Cada jogo conta uma única vez: devolve `false` (e não muda nada) se esse id
     * já foi registrado — inclusive depois de restaurar um jogo salvo.
     */
    suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean

    /**
     * Como [record], para um jogo com aposta (§14.6): [stake] e o prêmio recebido ([payout]) entram na carteira na mesma
     * gravação da contagem (um jogo só credita uma vez). Sem carteira, só conta o jogo.
     */
    suspend fun recordBet(
        gameId: String,
        mode: GameMode,
        difficultyId: String,
        result: GameResult,
        stake: Int,
        payout: Int,
    ): Boolean = record(gameId, mode, difficultyId, result)

    /** Zera as estatísticas (os ids já registrados continuam lembrados, para não contar de novo). */
    suspend fun reset()
}

/** Estatísticas no DataStore Preferences: um contador por (modo, dificuldade, campo) e os ids recentes já contados. */
class DataStoreStatsRepository(private val store: DataStore<Preferences>) : StatsRepository, WalletRepository, ShopLedger {

    /** Arquivo ilegível: estatísticas zeradas (nunca derruba o app). */
    override val stats: Flow<GameStats> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        val lines = mutableMapOf<StatsKey, StatsLine>()
        prefs.asMap().forEach { (key, value) ->
            val parts = key.name.split(':')
            if (parts.size != 4 || parts[0] != PREFIX || value !is Int) return@forEach
            val mode = GameMode.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
            val statsKey = StatsKey(mode, parts[2])
            val line = lines[statsKey] ?: StatsLine()
            lines[statsKey] = when (parts[3]) {
                GAMES -> line.copy(games = value)
                WINS -> line.copy(wins = value)
                LOSSES -> line.copy(losses = value)
                RESIGNATIONS -> line.copy(resignations = value)
                else -> line
            }
        }
        GameStats(lines)
    }

    override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean =
        recordBet(gameId, mode, difficultyId, result, stake = 0, payout = 0)

    override suspend fun recordBet(
        gameId: String,
        mode: GameMode,
        difficultyId: String,
        result: GameResult,
        stake: Int,
        payout: Int,
    ): Boolean {
        var recorded = false
        store.edit { prefs ->
            val ids = prefs[RECORDED_IDS].orEmpty().split(',').filter { it.isNotEmpty() }
            if (gameId in ids) return@edit
            fun bump(field: String) {
                val key = counter(mode, difficultyId, field)
                prefs[key] = (prefs[key] ?: 0) + 1
            }
            bump(GAMES)
            bump(
                when (result) {
                    GameResult.WIN -> WINS
                    GameResult.LOSS -> LOSSES
                    GameResult.RESIGNATION -> RESIGNATIONS
                },
            )
            prefs[RECORDED_IDS] = (ids + gameId).takeLast(MAX_REMEMBERED_IDS).joinToString(",")
            // §14.6 o prêmio entra na carteira junto com a contagem do jogo: um jogo só credita uma vez.
            if (stake > 0 || payout > 0) {
                prefs[BALANCE] = (prefs[BALANCE] ?: Coins.INITIAL_BALANCE).coerceAtLeast(0) + payout
                prefs[NET] = (prefs[NET] ?: 0) + payout - stake
            }
            recorded = true
        }
        return recorded
    }

    /** §14.6 carteira no mesmo arquivo das estatísticas (para o prêmio e a contagem serem gravados juntos). */
    override val wallet: Flow<Wallet> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs -> walletOf(prefs) }

    override val lifetimeNet: Flow<Int> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs -> prefs[NET] ?: 0 }

    override suspend fun debit(stake: Int): Boolean {
        var done = false
        store.edit { prefs ->
            val debited = walletOf(prefs).debit(stake) ?: return@edit
            prefs[BALANCE] = debited.balance
            done = true
        }
        return done
    }

    override suspend fun claimDaily(today: Long): Boolean {
        var done = false
        store.edit { prefs ->
            val claimed = walletOf(prefs).claimDaily(today) ?: return@edit
            prefs[BALANCE] = claimed.balance
            prefs[LAST_CLAIM_DAY] = today
            done = true
        }
        return done
    }

    override suspend fun debitPurchase(purchaseId: String, offer: ShopOffer): Boolean {
        var done = false
        store.edit { prefs ->
            val debited = walletOf(prefs).debit(offer.price) ?: return@edit
            prefs[BALANCE] = debited.balance
            prefs[SHOP_PENDING] = "$purchaseId|${offer.boosterId}|${offer.quantity}"
            done = true
        }
        return done
    }

    override suspend fun pendingPurchase(): PendingPurchase? {
        val parts = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()[SHOP_PENDING]?.split('|') ?: return null
        val quantity = parts.getOrNull(2)?.toIntOrNull() ?: return null
        return if (parts.size == 3 && quantity > 0) PendingPurchase(parts[0], parts[1], quantity) else null
    }

    override suspend fun clearPurchase(purchaseId: String) {
        store.edit { prefs -> if (prefs[SHOP_PENDING]?.startsWith("$purchaseId|") == true) prefs.remove(SHOP_PENDING) }
    }

    private fun walletOf(prefs: Preferences): Wallet =
        Wallet((prefs[BALANCE] ?: Coins.INITIAL_BALANCE).coerceAtLeast(0), prefs[LAST_CLAIM_DAY])

    override suspend fun reset() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("$PREFIX:") }.forEach { prefs.remove(it) }
        }
    }

    private companion object {
        const val PREFIX = "stats"
        const val GAMES = "games"
        const val WINS = "wins"
        const val LOSSES = "losses"
        const val RESIGNATIONS = "resignations"

        /** Basta lembrar os últimos ids: só há um jogo salvo por vez, então só o atual pode ser recontado. */
        const val MAX_REMEMBERED_IDS = 50
        val RECORDED_IDS = stringPreferencesKey("recorded_game_ids")

        // §14.6 carteira (chaves fora do prefixo "stats": o reset das estatísticas não mexe nelas)
        val BALANCE = intPreferencesKey("wallet:balance")
        val LAST_CLAIM_DAY = longPreferencesKey("wallet:lastClaimDay")
        val NET = intPreferencesKey("wallet:net")

        /** §17.10 compra da loja já debitada e ainda não concluída ("id|booster|quantidade"). */
        val SHOP_PENDING = stringPreferencesKey("shop:pending")

        fun counter(mode: GameMode, difficultyId: String, field: String): Preferences.Key<Int> =
            intPreferencesKey("$PREFIX:${mode.name}:$difficultyId:$field")
    }
}
