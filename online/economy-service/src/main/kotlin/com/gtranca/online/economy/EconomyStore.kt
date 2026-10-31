package com.gtranca.online.economy

import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Coins
import com.gtranca.engine.Inventory
import com.gtranca.engine.Shop
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.PayoutKind
import java.sql.Connection
import javax.sql.DataSource

/** Saldo e inventário de uma conta (§18.5). */
data class EconomyState(val balance: Long, val inventory: Map<String, Int>, val lastDailyClaim: Long?)

/** Recusa de uma operação da economia, com o motivo em pt-BR. */
class EconomyException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code { SALDO_INSUFICIENTE, JA_RESGATADO, OFERTA_INEXISTENTE, CONTA_INEXISTENTE, REQUISICAO_INVALIDA, APOSTA_INEXISTENTE }
}

/**
 * Carteira e inventário online no Postgres (§18.5). Cada operação é uma transação que trava a carteira da conta
 * (`FOR UPDATE`): o saldo nunca fica negativo, mesmo com pedidos simultâneos. Cada mudança vira um lançamento com uma
 * **chave de idempotência** única por conta: repetir a operação com a mesma chave devolve o estado atual sem mudar nada.
 */
class EconomyStore(private val db: DataSource) {

    /**
     * Garante a conta (convidado, por ora) com a carteira inicial de §14.6 e os boosters iniciais de §17.1. Idempotente:
     * chamar de novo não muda nada.
     */
    fun ensureAccount(accountId: String, kind: String = "CONVIDADO"): EconomyState = tx { c ->
        val created = c.prepareStatement("INSERT INTO conta (id, tipo) VALUES (?, ?) ON CONFLICT (id) DO NOTHING").use {
            it.setString(1, accountId)
            it.setString(2, kind)
            it.executeUpdate() == 1
        }
        if (created) {
            c.prepareStatement("INSERT INTO carteira (conta_id, saldo) VALUES (?, 0)").use { it.setString(1, accountId); it.executeUpdate() }
            apply(c, accountId, "SALDO_INICIAL", "saldo-inicial", coins = Coins.INITIAL_BALANCE.toLong())
            Inventory.initial(BoosterCatalog.DEFAULT).quantities.forEach { (id, qty) ->
                apply(c, accountId, "INVENTARIO_INICIAL", "inventario-inicial-$id", boosterId = id, quantity = qty)
            }
        }
        read(c, accountId)
    }

    fun state(accountId: String): EconomyState = tx { c -> read(c, accountId) }

    /** §14.6 ganho diário de 200 moedas, uma vez por dia ([today] em dias desde a época, no fuso do jogador). */
    fun claimDaily(accountId: String, today: Long): EconomyState = tx { c ->
        val last = lockWallet(c, accountId).second
        if (last != null && today <= last) throw EconomyException(EconomyException.Code.JA_RESGATADO, "O ganho de hoje já foi resgatado.")
        apply(c, accountId, "GANHO_DIARIO", "ganho-diario-$today", coins = Coins.DAILY_GAIN.toLong())
        c.prepareStatement("UPDATE carteira SET ultimo_resgate = ? WHERE conta_id = ?").use {
            it.setLong(1, today)
            it.setString(2, accountId)
            it.executeUpdate()
        }
        read(c, accountId)
    }

    /**
     * §17.10 compra na loja: debita o valor e soma os boosters numa transação só (nunca um sem o outro). [key] identifica o
     * pedido (o app repete a mesma chave se a rede falhar): a compra repetida não cobra de novo.
     */
    fun buy(accountId: String, offerIndex: Int, key: String): EconomyState = tx { c ->
        val offer = Shop.OFFERS.getOrNull(offerIndex)
            ?: throw EconomyException(EconomyException.Code.OFERTA_INEXISTENTE, "Oferta inexistente.")
        val balance = lockWallet(c, accountId).first
        if (alreadyApplied(c, accountId, "compra-$key")) return@tx read(c, accountId)
        if (balance < offer.price) throw EconomyException(EconomyException.Code.SALDO_INSUFICIENTE, "Saldo insuficiente para esta compra.")
        apply(c, accountId, "COMPRA", "compra-$key", coins = -offer.price.toLong(), reference = "oferta-$offerIndex")
        apply(c, accountId, "COMPRA", "compra-$key-booster", boosterId = offer.boosterId, quantity = offer.quantity, reference = "oferta-$offerIndex")
        read(c, accountId)
    }

    /**
     * §18.2 a aposta é **retida** ao iniciar o jogo, de todos os humanos numa transação só. As carteiras são travadas em
     * ordem de id (sem deadlock entre jogos simultâneos). Quem não tem mais saldo fica de fora (§14.6: joga **sem aposta**,
     * nada debitado nem pago). Idempotente por ([betId], conta): repetir devolve o mesmo resultado sem reter de novo.
     * @return as contas com aposta retida.
     */
    fun holdBets(betId: String, amount: Long, accounts: List<String>): Set<String> {
        if (amount <= 0 || betId.isBlank()) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Aposta inválida.")
        accounts.forEach { ensureAccount(it) }
        return tx { c ->
            val held = mutableSetOf<String>()
            for (id in accounts.distinct().sorted()) {
                val balance = lockWallet(c, id).first
                if (betRow(c, betId, id, lock = true) != null) { held += id; continue }
                if (balance < amount) continue
                c.prepareStatement("INSERT INTO aposta (mesa_id, conta_id, valor, estado) VALUES (?, ?, ?, 'RETIDA')").use {
                    it.setString(1, betId); it.setString(2, id); it.setLong(3, amount); it.executeUpdate()
                }
                apply(c, id, "APOSTA", "aposta-$betId", coins = -amount, reference = betId)
                held += id
            }
            held
        }
    }

    /**
     * Liquida a aposta (§14.6, §16.13, §18.4): prêmio, devolução ou perda por conta, numa transação só. Aposta já
     * LIQUIDADA não muda (repetir por falha de rede nunca paga duas vezes). Conta sem aposta retida neste [betId] é
     * recusada (nunca se ganha sem apostar). Devolução = a aposta inteira; perda = 0; prêmio até 4× a aposta.
     * @return (liquidadas agora, já liquidadas antes).
     */
    fun settleBets(betId: String, payouts: List<BetPayout>): Pair<Int, Int> = tx { c ->
        if (payouts.map { it.accountId }.toSet().size != payouts.size) {
            throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Conta repetida na liquidação.")
        }
        var done = 0
        var before = 0
        for (p in payouts.sortedBy { it.accountId }) {
            lockWallet(c, p.accountId)
            val (value, state) = betRow(c, betId, p.accountId, lock = true)
                ?: throw EconomyException(EconomyException.Code.APOSTA_INEXISTENTE, "Aposta não encontrada.")
            if (state == "LIQUIDADA") { before++; continue }
            val ok = when (p.kind) {
                PayoutKind.DEVOLUCAO -> p.amount == value
                PayoutKind.PERDA -> p.amount == 0L
                PayoutKind.PREMIO -> p.amount in 0..value * 4
            }
            if (!ok) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Valor de liquidação inválido.")
            if (p.amount > 0) apply(c, p.accountId, p.kind.name, "liquidacao-$betId", coins = p.amount, reference = betId)
            c.prepareStatement(
                "UPDATE aposta SET estado = 'LIQUIDADA', resultado = ?, recebido = ?, liquidada_em = now() WHERE mesa_id = ? AND conta_id = ?",
            ).use {
                it.setString(1, p.kind.name); it.setLong(2, p.amount); it.setString(3, betId); it.setString(4, p.accountId)
                it.executeUpdate()
            }
            done++
        }
        done to before
    }

    /** (valor, estado) da aposta, ou `null`. */
    fun bet(betId: String, accountId: String): Pair<Long, String>? = tx { c -> betRow(c, betId, accountId, lock = false) }

    // ---------------------------------------------------------------------------------------------- apoio

    private fun betRow(c: Connection, betId: String, accountId: String, lock: Boolean): Pair<Long, String>? =
        c.prepareStatement("SELECT valor, estado FROM aposta WHERE mesa_id = ? AND conta_id = ?" + if (lock) " FOR UPDATE" else "").use { st ->
            st.setString(1, betId)
            st.setString(2, accountId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        }

    /** Trava a carteira e devolve (saldo, último resgate). */
    private fun lockWallet(c: Connection, accountId: String): Pair<Long, Long?> =
        c.prepareStatement("SELECT saldo, ultimo_resgate FROM carteira WHERE conta_id = ? FOR UPDATE").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw EconomyException(EconomyException.Code.CONTA_INEXISTENTE, "Conta não encontrada.")
                rs.getLong(1) to rs.getLong(2).takeUnless { rs.wasNull() }
            }
        }

    private fun alreadyApplied(c: Connection, accountId: String, key: String): Boolean =
        c.prepareStatement("SELECT 1 FROM lancamento WHERE conta_id = ? AND chave = ?").use { st ->
            st.setString(1, accountId)
            st.setString(2, key)
            st.executeQuery().use { it.next() }
        }

    /**
     * Um lançamento: grava no livro-razão e aplica ao saldo ou ao inventário. Chave repetida = já aplicado, nada muda.
     * O `CHECK` do banco recusa saldo ou quantidade negativos (a transação inteira volta).
     */
    private fun apply(
        c: Connection, accountId: String, reason: String, key: String,
        coins: Long = 0, boosterId: String? = null, quantity: Int = 0, reference: String? = null,
    ): Boolean {
        val inserted = c.prepareStatement(
            "INSERT INTO lancamento (conta_id, motivo, moedas, booster_id, quantidade, referencia, chave) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (conta_id, chave) DO NOTHING",
        ).use {
            it.setString(1, accountId)
            it.setString(2, reason)
            it.setLong(3, coins)
            it.setString(4, boosterId)
            it.setInt(5, quantity)
            it.setString(6, reference)
            it.setString(7, key)
            it.executeUpdate() == 1
        }
        if (!inserted) return false
        if (coins != 0L) {
            c.prepareStatement("UPDATE carteira SET saldo = saldo + ? WHERE conta_id = ?").use {
                it.setLong(1, coins)
                it.setString(2, accountId)
                it.executeUpdate()
            }
        }
        if (boosterId != null && quantity != 0) {
            c.prepareStatement(
                "INSERT INTO inventario (conta_id, booster_id, quantidade) VALUES (?, ?, ?) " +
                    "ON CONFLICT (conta_id, booster_id) DO UPDATE SET quantidade = inventario.quantidade + EXCLUDED.quantidade",
            ).use {
                it.setString(1, accountId)
                it.setString(2, boosterId)
                it.setInt(3, quantity)
                it.executeUpdate()
            }
        }
        return true
    }

    private fun read(c: Connection, accountId: String): EconomyState {
        val (balance, last) = c.prepareStatement("SELECT saldo, ultimo_resgate FROM carteira WHERE conta_id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw EconomyException(EconomyException.Code.CONTA_INEXISTENTE, "Conta não encontrada.")
                rs.getLong(1) to rs.getLong(2).takeUnless { rs.wasNull() }
            }
        }
        val inventory = c.prepareStatement("SELECT booster_id, quantidade FROM inventario WHERE conta_id = ? ORDER BY booster_id").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }
        return EconomyState(balance, inventory, last)
    }

    private fun <T> tx(block: (Connection) -> T): T = db.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }
}
