package com.gtranca.online.economy

import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Coins
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import com.gtranca.engine.Shop
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.PayoutKind
import java.sql.Connection
import javax.sql.DataSource

/** Saldo e inventário de uma conta (§18.5). [migratedLocal]: a conta já trouxe a carteira do aparelho (§18.6). */
data class EconomyState(val balance: Long, val inventory: Map<String, Int>, val lastDailyClaim: Long?, val migratedLocal: Boolean = false)

/** Recusa de uma operação da economia, com o motivo em pt-BR. */
class EconomyException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        SALDO_INSUFICIENTE, JA_RESGATADO, OFERTA_INEXISTENTE, CONTA_INEXISTENTE, REQUISICAO_INVALIDA, APOSTA_INEXISTENTE,
        CONTA_NAO_ELEGIVEL,
    }
}

/**
 * Carteira e inventário online no Postgres (§18.5). Cada operação é uma transação que trava a carteira da conta
 * (`FOR UPDATE`): o saldo nunca fica negativo, mesmo com pedidos simultâneos. Cada mudança vira um lançamento com uma
 * **chave de idempotência** única por conta: repetir a operação com a mesma chave devolve o estado atual sem mudar nada.
 */
class EconomyStore(private val db: DataSource, private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        /**
         * §18.6 não impõe teto de produto ("sem teto"): isto só evita overflow/estouro, no limite do que a carteira
         * LOCAL consegue guardar (`Wallet.balance`/`Inventory.quantities` são `Int`, core-engine) — nenhum saldo ou
         * quantidade local genuíno jamais passa disto, então "sem teto" continua literal.
         */
        private const val MAX_MIGRATED_COINS = Int.MAX_VALUE.toLong()
        private const val MAX_MIGRATED_BOOSTER_QTY = Int.MAX_VALUE

        /** Chave de idempotência fixa por conta: existir já significa "esta conta já migrou" (§18.6). */
        private fun migrationKey(accountId: String) = "migracao-carteira-$accountId"
    }

    /**
     * Garante a conta (convidado, por ora) com a carteira inicial de §14.6 e os boosters iniciais de §17.1. Idempotente:
     * chamar de novo não muda nada.
     */
    fun ensureAccount(accountId: String, kind: String = kindOf(accountId)): EconomyState = tx { c ->
        if (insertAccount(c, accountId, kind)) seedInitial(c, accountId)
        read(c, accountId)
    }

    /**
     * §18.5 "Do convidado para a conta": no login Google, se a conta [accountId] **ainda nao tem** carteira, ela nasce com a
     * carteira e o inventario do convidado [guestId] (uma vez so por convidado; o convidado fica zerado e marcado). Se a
     * conta ja tem carteira, nada muda (a do convidado fica com ele). Sem convidado (ou ja migrado), a conta nasce com o
     * saldo e os boosters iniciais. Idempotente: repetir nao muda nada.
     * @return (a carteira do convidado passou agora, estado da conta).
     */
    fun adoptGuestWallet(accountId: String, guestId: String?): Pair<Boolean, EconomyState> {
        // A carteira do convidado precisa existir para ser travada (convidado que nunca abriu a carteira ganha a inicial).
        guestId?.takeIf { it != accountId && kindOf(it) == "CONVIDADO" }?.let { ensureAccount(it) }
        return tx { c ->
            // §18.1: a conta pode ser Google ou e-mail; o tipo vem da forma do id (prefixo), nunca de um parametro fixo.
            if (!insertAccount(c, accountId, kindOf(accountId))) return@tx false to read(c, accountId)
            val guest = guestId?.takeIf { it != accountId && kindOf(it) == "CONVIDADO" }
            val (balance, lastDaily) = guest?.let { lockWallet(c, it) } ?: (0L to null)
            val free = guest != null && c.prepareStatement("SELECT tipo = 'CONVIDADO' AND migrada_para IS NULL FROM conta WHERE id = ? FOR UPDATE").use { st ->
                st.setString(1, guest)
                st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (!free) {
                seedInitial(c, accountId)
                return@tx false to read(c, accountId)
            }
            guest!!
            val inventory = read(c, guest).inventory
            apply(c, guest, "MIGRACAO_PARA_CONTA", "migracao-para-conta", coins = -balance, reference = accountId)
            apply(c, accountId, "MIGRACAO_DO_CONVIDADO", "migracao-do-convidado", coins = balance, reference = guest)
            inventory.filterValues { it > 0 }.forEach { (id, qty) ->
                apply(c, guest, "MIGRACAO_PARA_CONTA", "migracao-para-conta-$id", boosterId = id, quantity = -qty, reference = accountId)
                apply(c, accountId, "MIGRACAO_DO_CONVIDADO", "migracao-do-convidado-$id", boosterId = id, quantity = qty, reference = guest)
            }
            // O ganho diario ja resgatado pelo convidado hoje vale para a conta (sem resgatar duas vezes no mesmo dia).
            c.prepareStatement("UPDATE carteira SET ultimo_resgate = ? WHERE conta_id = ?").use {
                if (lastDaily == null) it.setNull(1, java.sql.Types.BIGINT) else it.setLong(1, lastDaily)
                it.setString(2, accountId)
                it.executeUpdate()
            }
            c.prepareStatement("UPDATE conta SET migrada_para = ? WHERE id = ?").use { it.setString(1, accountId); it.setString(2, guest); it.executeUpdate() }
            c.prepareStatement("UPDATE conta SET origem_convidado = ? WHERE id = ?").use { it.setString(1, guest); it.setString(2, accountId); it.executeUpdate() }
            true to read(c, accountId)
        }
    }

    private fun insertAccount(c: Connection, accountId: String, kind: String): Boolean {
        val created = c.prepareStatement("INSERT INTO conta (id, tipo) VALUES (?, ?) ON CONFLICT (id) DO NOTHING").use {
            it.setString(1, accountId)
            it.setString(2, kind)
            it.executeUpdate() == 1
        }
        if (created) c.prepareStatement("INSERT INTO carteira (conta_id, saldo) VALUES (?, 0)").use { it.setString(1, accountId); it.executeUpdate() }
        return created
    }

    /** §14.6 saldo inicial e §17.1 boosters iniciais. */
    private fun seedInitial(c: Connection, accountId: String) {
        apply(c, accountId, "SALDO_INICIAL", "saldo-inicial", coins = Coins.INITIAL_BALANCE.toLong())
        Inventory.initial(BoosterCatalog.DEFAULT).quantities.forEach { (id, qty) ->
            apply(c, accountId, "INVENTARIO_INICIAL", "inventario-inicial-$id", boosterId = id, quantity = qty)
        }
    }

    /** Tipo pela forma do id: contas Google tem o prefixo `G_` (plano 2.6), contas de e-mail o prefixo `E_` (§18.1). */
    fun kindOf(accountId: String): String = when {
        accountId.startsWith(AccountLimits.GOOGLE_ID_PREFIX) -> "GOOGLE"
        accountId.startsWith(AccountLimits.EMAIL_ID_PREFIX) -> "EMAIL"
        else -> "CONVIDADO"
    }

    fun state(accountId: String): EconomyState = tx { c -> read(c, accountId) }

    /**
     * §14.6 ganho diário de 200 moedas, uma vez por **dia real**. Quem decide "hoje" é o **servidor**, pelo próprio
     * relógio (UTC) — nunca o aparelho: [today] (dias desde a época, no fuso do jogador) é só informação
     * complementar, ignorada aqui, para que adiantar o relógio do celular não libere o resgate de novo. A chave de
     * idempotência também usa o dia do servidor, não o [today] do cliente, senão a mesma brecha reaparece: um
     * `today` diferente a cada tentativa geraria uma chave nova e o livro-razão deixaria passar.
     *
     * O reinício acontece à meia-noite UTC, igual para todo mundo; ninguém fica bloqueado por mais de um dia real
     * (nunca "pune" quem está num fuso distante: o pior caso é resgatar perto da meia-noite UTC em vez da meia-noite
     * local, não deixar de resgatar).
     */
    fun claimDaily(accountId: String, today: Long): EconomyState = tx { c ->
        val day = serverDay()
        val last = lockWallet(c, accountId).second
        if (last != null && day <= last) throw EconomyException(EconomyException.Code.JA_RESGATADO, "O ganho de hoje já foi resgatado.")
        if (!apply(c, accountId, "GANHO_DIARIO", "ganho-diario-$day", coins = Coins.DAILY_GAIN.toLong())) {
            throw EconomyException(EconomyException.Code.JA_RESGATADO, "O ganho de hoje já foi resgatado.")
        }
        c.prepareStatement("UPDATE carteira SET ultimo_resgate = ? WHERE conta_id = ?").use {
            it.setLong(1, day)
            it.setString(2, accountId)
            it.executeUpdate()
        }
        read(c, accountId)
    }

    /** "Hoje" pelo relógio do próprio servidor (dia UTC desde a época); nunca pelo que o cliente informa (§14.6). */
    private fun serverDay(): Long = Math.floorDiv(clock(), MILLIS_PER_DAY)

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
     * §18.6 migração da carteira local: soma [coins] ao saldo online e cada booster de [inventory] (quantidade > 0) ao
     * inventário online, **uma única vez por conta**. Só vale para conta (e-mail/Google, nunca convidado — a
     * identidade dele não é duradoura o bastante para uma operação irreversível de uma vez só). A chave de
     * idempotência é **fixa** por conta ([migrationKey]): repetir a chamada (retry de rede, duplo toque) nunca soma
     * duas vezes, e tentar migrar de novo depois de já ter migrado só devolve o estado atual, sem creditar nada —
     * nem erro. O saldo e o inventário **locais** não são alterados aqui (o aparelho é quem decide isso, não o
     * servidor). O servidor não tem autoridade sobre o save local: confia inteiramente no que a requisição manda,
     * só com limites defensivos contra overflow (§18.6 não pede teto de produto).
     */
    fun migrateLocalWallet(accountId: String, coins: Long, inventory: Map<String, Int>): EconomyState {
        if (kindOf(accountId) == "CONVIDADO") {
            throw EconomyException(EconomyException.Code.CONTA_NAO_ELEGIVEL, "Convidado não pode migrar a carteira local.")
        }
        if (coins < 0 || coins > MAX_MIGRATED_COINS) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Saldo inválido.")
        if (inventory.values.any { it < 0 || it > MAX_MIGRATED_BOOSTER_QTY }) {
            throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Inventário inválido.")
        }
        ensureAccount(accountId) // a conta normalmente ja existe (criada no login), mas nunca falha por isso aqui
        val catalog = BoosterCatalog.DEFAULT.map { it.id }.toSet()
        return tx { c ->
            lockWallet(c, accountId) // trava a carteira: serializa tentativas concorrentes da MESMA conta
            val key = migrationKey(accountId)
            if (alreadyApplied(c, accountId, key)) return@tx read(c, accountId)
            apply(c, accountId, "MIGRACAO_CARTEIRA_LOCAL", key, coins = coins)
            inventory.filterKeys { it in catalog }.filterValues { it > 0 }.forEach { (id, qty) ->
                apply(c, accountId, "MIGRACAO_CARTEIRA_LOCAL", "$key-$id", boosterId = id, quantity = qty)
            }
            read(c, accountId)
        }
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
                val existing = betRow(c, betId, id, lock = true)
                if (existing != null) { if (existing.first > 0) held += id; continue } // valor 0 = lapide (devolvida antes)
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
    fun settleBets(betId: String, payouts: List<BetPayout>): Pair<Int, Int> {
        payouts.filter { it.kind == PayoutKind.DEVOLUCAO }.forEach { ensureAccount(it.accountId) }
        return settleTx(betId, payouts)
    }

    private fun settleTx(betId: String, payouts: List<BetPayout>): Pair<Int, Int> = tx { c ->
        if (payouts.map { it.accountId }.toSet().size != payouts.size) {
            throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Conta repetida na liquidação.")
        }
        var done = 0
        var before = 0
        for (p in payouts.sortedBy { it.accountId }) {
            lockWallet(c, p.accountId)
            val row = betRow(c, betId, p.accountId, lock = true)
            if (row == null && p.kind == PayoutKind.DEVOLUCAO) {
                // Devolucao de uma retencao que nao aconteceu (falhou, sem saldo ou ainda nao chegou): grava uma
                // "lapide" LIQUIDADA sem lancamento. Uma retencao atrasada com o mesmo id encontra a linha e nao debita.
                c.prepareStatement(
                    "INSERT INTO aposta (mesa_id, conta_id, valor, estado, resultado, recebido, liquidada_em) " +
                        "VALUES (?, ?, 0, 'LIQUIDADA', 'DEVOLUCAO', 0, now())",
                ).use { it.setString(1, betId); it.setString(2, p.accountId); it.executeUpdate() }
                before++
                continue
            }
            val (value, state) = row
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

    /**
     * §17.3/§18.3: debita 1 de cada booster de [boosterIds] (ate [Loadout.MAX]) do inventario online de [accountId],
     * no inicio do jogo [matchId] (assento [seat]). Cada booster e uma checagem/debito independente dentro da mesma
     * transacao: o que nao tiver estoque fica de FORA, nunca interrompe os demais (como [holdBets], mas por booster em
     * vez de por conta). Id fora do catalogo e ignorado (nunca confia no chamador). Idempotente por (contaId, matchId,
     * assento, boosterId): repetir a chamada nao debita de novo (a chave do lancamento ja existe).
     */
    fun debitBoosters(matchId: String, seat: Int, accountId: String, boosterIds: List<String>): Set<String> {
        ensureAccount(accountId)
        val catalog = BoosterCatalog.DEFAULT.associateBy { it.id }
        return tx { c ->
            val applied = mutableSetOf<String>()
            // Filtra o catalogo ANTES de limitar a Loadout.MAX: um id invalido nunca toma a vaga de um booster valido.
            for (id in boosterIds.filter { it in catalog }.distinct().take(Loadout.MAX)) {
                val def = catalog.getValue(id)
                val key = "boosters-$matchId-$seat-$id"
                if (alreadyApplied(c, accountId, key)) {
                    applied += id
                    continue
                }
                if (lockBoosterQuantity(c, accountId, id) < def.cost) continue
                apply(c, accountId, "BOOSTERS_PARTIDA", key, boosterId = id, quantity = -def.cost, reference = matchId)
                applied += id
            }
            applied
        }
    }

    /** Trava a linha do inventario (`FOR UPDATE`); sem linha ainda (nunca debitado) vale 0. */
    private fun lockBoosterQuantity(c: Connection, accountId: String, boosterId: String): Int =
        c.prepareStatement("SELECT quantidade FROM inventario WHERE conta_id = ? AND booster_id = ? FOR UPDATE").use { st ->
            st.setString(1, accountId)
            st.setString(2, boosterId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

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
        // Debito vira UPDATE: o CHECK (quantidade >= 0) vale para a linha proposta do INSERT antes do ON CONFLICT.
        if (boosterId != null && quantity < 0) {
            c.prepareStatement("UPDATE inventario SET quantidade = quantidade + ? WHERE conta_id = ? AND booster_id = ?").use {
                it.setInt(1, quantity)
                it.setString(2, accountId)
                it.setString(3, boosterId)
                it.executeUpdate()
            }
        } else if (boosterId != null && quantity > 0) {
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
        val migratedLocal = alreadyApplied(c, accountId, migrationKey(accountId))
        return EconomyState(balance, inventory, last, migratedLocal)
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
