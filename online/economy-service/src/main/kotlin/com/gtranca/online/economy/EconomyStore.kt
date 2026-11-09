package com.gtranca.online.economy

import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Coins
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import com.gtranca.engine.Shop
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.EconomyLimits
import com.gtranca.online.contracts.PayoutKind
import java.sql.Connection
import javax.sql.DataSource

/** Saldo e inventário de uma conta (§18.5). [migratedLocal]: a conta já trouxe a carteira do aparelho (§18.6). */
data class EconomyState(val balance: Long, val inventory: Map<String, Int>, val lastDailyClaim: Long?, val migratedLocal: Boolean = false)

/** Uma linha do livro-razão (§18.7), como gravada em `lancamento`. */
data class LedgerEntry(
    val reason: String,
    val coins: Long,
    val boosterId: String?,
    val quantity: Int,
    val reference: String?,
    val detail: String?,
    val key: String,
    val createdAtMs: Long,
)

/** Uma migração de carteira local já feita (§18.6/§18.7): só a linha de moedas (sem os boosters da mesma operação). */
data class MigrationEntry(val accountId: String, val coins: Long, val createdAtMs: Long)

/** Um campo configurável da loja/ganho diário (§18.7): o valor atual, se veio do `:core-engine` ou de um override, e desde quando. */
data class ConfigField(val value: Long, val isOverride: Boolean, val sinceMs: Long?)

/** Preço e quantidade atuais de uma oferta da loja (§17.10/§18.7). [boosterId] é fixo (vem do catálogo). */
data class OfferConfig(val index: Int, val boosterId: String, val price: ConfigField, val quantity: ConfigField)

/** O snapshot inteiro de GET /internal/admin/shop-config (§18.7). */
data class ShopConfigSnapshot(val offers: List<OfferConfig>, val dailyGain: ConfigField)

/** Uma linha do histórico de `config_loja` (§18.7), como gravada (append-only, nunca `UPDATE`). */
data class ConfigHistoryEntry(
    val field: String,
    val value: Long,
    val previousValue: Long?,
    val reason: String,
    val operationId: String,
    val createdAtMs: Long,
)

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

        /** §14.6/§18.7: chave de `config_loja` do ganho diário (único campo que não é por oferta). */
        private const val DAILY_GAIN_KEY = "ganho-diario"

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
        val gain = configField(c, DAILY_GAIN_KEY, Coins.DAILY_GAIN.toLong()).value
        val last = lockWallet(c, accountId).second
        if (last != null && day <= last) throw EconomyException(EconomyException.Code.JA_RESGATADO, "O ganho de hoje já foi resgatado.")
        if (!apply(c, accountId, "GANHO_DIARIO", "ganho-diario-$day", coins = gain)) {
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
        // §18.7 "Loja e configuracao": preco e quantidade podem ter sido mudados pelo administrador; sem override,
        // valem os mesmos de Shop.OFFERS (nunca uma copia desatualizada: le dentro da mesma transacao da compra).
        val price = configField(c, offerPriceKey(offerIndex), offer.price.toLong()).value
        val quantity = configField(c, offerQuantityKey(offerIndex), offer.quantity.toLong()).value
        val balance = lockWallet(c, accountId).first
        if (alreadyApplied(c, accountId, "compra-$key")) return@tx read(c, accountId)
        if (balance < price) throw EconomyException(EconomyException.Code.SALDO_INSUFICIENTE, "Saldo insuficiente para esta compra.")
        apply(c, accountId, "COMPRA", "compra-$key", coins = -price, reference = "oferta-$offerIndex")
        apply(c, accountId, "COMPRA", "compra-$key-booster", boosterId = offer.boosterId, quantity = quantity.toInt(), reference = "oferta-$offerIndex")
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
     *
     * Um id de booster fora do catálogo é **recusado** (não silenciosamente ignorado como em [debitBoosters]): aqui
     * a operação é de uma vez só e §18.6 promete que "o inventário migra junto" — perder um booster para sempre por
     * descompasso de versão entre app e servidor seria pior que o jogador tentar de novo depois de atualizar.
     */
    fun migrateLocalWallet(accountId: String, coins: Long, inventory: Map<String, Int>): EconomyState {
        if (kindOf(accountId) == "CONVIDADO") {
            throw EconomyException(EconomyException.Code.CONTA_NAO_ELEGIVEL, "Convidado não pode migrar a carteira local.")
        }
        if (coins < 0 || coins > MAX_MIGRATED_COINS) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Saldo inválido.")
        if (inventory.values.any { it < 0 || it > MAX_MIGRATED_BOOSTER_QTY }) {
            throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Inventário inválido.")
        }
        val catalog = BoosterCatalog.DEFAULT.map { it.id }.toSet()
        if (inventory.keys.any { it !in catalog }) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Booster inválido.")
        ensureAccount(accountId) // a conta normalmente ja existe (criada no login), mas nunca falha por isso aqui
        return tx { c ->
            lockWallet(c, accountId) // trava a carteira: serializa tentativas concorrentes da MESMA conta
            val key = migrationKey(accountId)
            if (alreadyApplied(c, accountId, key)) return@tx read(c, accountId)
            apply(c, accountId, "MIGRACAO_CARTEIRA_LOCAL", key, coins = coins)
            inventory.filterValues { it > 0 }.forEach { (id, qty) ->
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

    /**
     * §18.7 "ajustar saldo ou booster": o operador soma (negativo para subtrair) moedas e/ou a quantidade de UM
     * booster, sempre com [reason] (texto livre, gravado como [LedgerEntry.detail], motivo fixo `AJUSTE`) e
     * idempotente por [operationId] (repetir a mesma chamada — retry de rede do admin-api — nunca ajusta duas
     * vezes). Mesma regra de sempre: saldo e quantidade NUNCA ficam negativos, mesmo sendo o operador — valida
     * ANTES de aplicar (como [buy]/[debitBoosters]), nunca deixa o `CHECK` do banco ser a única defesa.
     */
    fun adjustWallet(accountId: String, reason: String, operationId: String, coinsDelta: Long, boosterId: String?, quantityDelta: Int): EconomyState {
        if (boosterId != null && !BoosterCatalog.DEFAULT.any { it.id == boosterId }) {
            throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Booster inválido.")
        }
        ensureAccount(accountId)
        val key = "ajuste-$operationId"
        return tx { c ->
            if (alreadyApplied(c, accountId, key)) return@tx read(c, accountId)
            if (coinsDelta < 0) {
                val balance = lockWallet(c, accountId).first
                if (balance + coinsDelta < 0) throw EconomyException(EconomyException.Code.SALDO_INSUFICIENTE, "O ajuste deixaria o saldo negativo.")
            }
            if (boosterId != null && quantityDelta < 0) {
                val quantity = lockBoosterQuantity(c, accountId, boosterId)
                if (quantity + quantityDelta < 0) {
                    throw EconomyException(EconomyException.Code.SALDO_INSUFICIENTE, "O ajuste deixaria a quantidade do booster negativa.")
                }
            }
            // A linha de moedas e sempre gravada primeiro (mesmo com coinsDelta = 0): e ela que marca a
            // idempotencia do ajuste inteiro, inclusive quando so o booster muda.
            apply(c, accountId, "AJUSTE", key, coins = coinsDelta, detail = reason)
            if (boosterId != null && quantityDelta != 0) {
                apply(c, accountId, "AJUSTE", "$key-booster", boosterId = boosterId, quantity = quantityDelta, detail = reason)
            }
            read(c, accountId)
        }
    }

    /**
     * §18.5/§18.7: o livro-razão de UMA conta, mais recente primeiro (usa o índice `lancamento_conta`). Só
     * leitura: nunca cria a conta (ao contrário de [ensureAccount]) — conta inexistente é [EconomyException.Code.CONTA_INEXISTENTE].
     * @return (total sem paginar, a página pedida).
     */
    fun ledger(accountId: String, limit: Int, offset: Int): Pair<Int, List<LedgerEntry>> = tx { c ->
        requireAccountExists(c, accountId)
        val total = c.prepareStatement("SELECT count(*) FROM lancamento WHERE conta_id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        val entries = c.prepareStatement(
            "SELECT motivo, moedas, booster_id, quantidade, referencia, detalhe, chave, criado_em FROM lancamento " +
                "WHERE conta_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
        ).use { st ->
            st.setString(1, accountId)
            st.setInt(2, limit.coerceIn(1, EconomyLimits.MAX_ADMIN_PAGE_LIMIT))
            st.setInt(3, offset.coerceAtLeast(0))
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LedgerEntry(
                                reason = rs.getString(1), coins = rs.getLong(2), boosterId = rs.getString(3),
                                quantity = rs.getInt(4), reference = rs.getString(5), detail = rs.getString(6),
                                key = rs.getString(7), createdAtMs = rs.getTimestamp(8).time,
                            ),
                        )
                    }
                }
            }
        }
        total to entries
    }

    /**
     * §18.6/§18.7: migrações de carteira local JÁ feitas (motivo `MIGRACAO_CARTEIRA_LOCAL`), de TODAS as contas,
     * mais recente primeiro — para o operador revisar (§18.6 "o operador pode revisar migrações"; é a pendência
     * de detectar migrações suspeitas registrada em `docs/seguranca-pendencias.md`). Só a linha de MOEDAS de cada
     * migração ([booster_id IS NULL]): os boosters migrados na mesma operação têm o mesmo motivo com outra
     * chave (ver [migrateLocalWallet]: `$key-$boosterId`).
     */
    fun migrations(limit: Int, offset: Int): Pair<Int, List<MigrationEntry>> = tx { c ->
        val total = c.prepareStatement(
            "SELECT count(*) FROM lancamento WHERE motivo = 'MIGRACAO_CARTEIRA_LOCAL' AND booster_id IS NULL",
        ).use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        val entries = c.prepareStatement(
            "SELECT conta_id, moedas, criado_em FROM lancamento WHERE motivo = 'MIGRACAO_CARTEIRA_LOCAL' AND booster_id IS NULL " +
                "ORDER BY id DESC LIMIT ? OFFSET ?",
        ).use { st ->
            st.setInt(1, limit.coerceIn(1, EconomyLimits.MAX_ADMIN_PAGE_LIMIT))
            st.setInt(2, offset.coerceAtLeast(0))
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationEntry(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).time)) }
            }
        }
        total to entries
    }

    /**
     * §17.10/§14.6/§18.7 "Loja e configuração": o preço/quantidade ATUAL de cada oferta (default do `:core-engine`
     * ou override do administrador) e o valor atual do ganho diário. Só leitura.
     */
    fun shopConfig(): ShopConfigSnapshot = tx { c ->
        val offers = Shop.OFFERS.mapIndexed { index, offer ->
            OfferConfig(
                index, offer.boosterId,
                price = configField(c, offerPriceKey(index), offer.price.toLong()),
                quantity = configField(c, offerQuantityKey(index), offer.quantity.toLong()),
            )
        }
        ShopConfigSnapshot(offers, configField(c, DAILY_GAIN_KEY, Coins.DAILY_GAIN.toLong()))
    }

    /** §18.7: histórico de `config_loja`, mais recente primeiro, de TODOS os campos (para o operador revisar). */
    fun shopConfigHistory(limit: Int, offset: Int): Pair<Int, List<ConfigHistoryEntry>> = tx { c ->
        val total = c.prepareStatement("SELECT count(*) FROM config_loja").use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        val entries = c.prepareStatement(
            "SELECT chave, valor, valor_anterior, motivo, operacao_id, criado_em FROM config_loja ORDER BY id DESC LIMIT ? OFFSET ?",
        ).use { st ->
            st.setInt(1, limit.coerceIn(1, EconomyLimits.MAX_ADMIN_PAGE_LIMIT))
            st.setInt(2, offset.coerceAtLeast(0))
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ConfigHistoryEntry(
                                field = rs.getString(1), value = rs.getLong(2),
                                previousValue = rs.getLong(3).takeUnless { rs.wasNull() },
                                reason = rs.getString(4), operationId = rs.getString(5), createdAtMs = rs.getTimestamp(6).time,
                            ),
                        )
                    }
                }
            }
        }
        total to entries
    }

    /**
     * §17.10/§18.7: muda o preço e/ou a quantidade da oferta [index]. Nunca aceita valor zero ou negativo (mesma
     * regra do catálogo fixo, [ShopOffer.init]). Idempotente por [operationId] (por campo: repetir não grava de
     * novo nenhum dos dois).
     */
    fun adjustOfferConfig(index: Int, price: Int?, quantity: Int?, reason: String, operationId: String): OfferConfig {
        val offer = Shop.OFFERS.getOrNull(index) ?: throw EconomyException(EconomyException.Code.OFERTA_INEXISTENTE, "Oferta inexistente.")
        if (price == null && quantity == null) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "Informe o preço ou a quantidade.")
        if (price != null && price <= 0) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "O preço deve ser maior que zero.")
        if (quantity != null && quantity <= 0) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "A quantidade deve ser maior que zero.")
        return tx { c ->
            price?.let { writeConfig(c, offerPriceKey(index), it.toLong(), offer.price.toLong(), reason, operationId) }
            quantity?.let { writeConfig(c, offerQuantityKey(index), it.toLong(), offer.quantity.toLong(), reason, operationId) }
            OfferConfig(
                index, offer.boosterId,
                price = configField(c, offerPriceKey(index), offer.price.toLong()),
                quantity = configField(c, offerQuantityKey(index), offer.quantity.toLong()),
            )
        }
    }

    /** §14.6/§18.7: muda o ganho diário (`Coins.DAILY_GAIN`). Nunca aceita valor zero ou negativo. */
    fun adjustDailyGainConfig(amount: Long, reason: String, operationId: String): ConfigField {
        if (amount <= 0) throw EconomyException(EconomyException.Code.REQUISICAO_INVALIDA, "O ganho diário deve ser maior que zero.")
        return tx { c ->
            writeConfig(c, DAILY_GAIN_KEY, amount, Coins.DAILY_GAIN.toLong(), reason, operationId)
            configField(c, DAILY_GAIN_KEY, Coins.DAILY_GAIN.toLong())
        }
    }

    private fun offerPriceKey(index: Int) = "oferta-$index-preco"
    private fun offerQuantityKey(index: Int) = "oferta-$index-quantidade"

    /** (valor, quando) da linha mais recente de [key] em `config_loja`, ou `null` sem nenhuma (default do core-engine). */
    private fun latestConfig(c: Connection, key: String): Pair<Long, Long>? =
        c.prepareStatement("SELECT valor, criado_em FROM config_loja WHERE chave = ? ORDER BY id DESC LIMIT 1").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getTimestamp(2).time else null }
        }

    /** O valor atual de [key]: a linha mais recente de `config_loja`, ou [default] (constante do `:core-engine`). */
    private fun configField(c: Connection, key: String, default: Long): ConfigField =
        latestConfig(c, key)?.let { (value, sinceMs) -> ConfigField(value, isOverride = true, sinceMs = sinceMs) }
            ?: ConfigField(default, isOverride = false, sinceMs = null)

    /**
     * Grava uma nova linha de [key] em `config_loja` (nunca `UPDATE`): [previousValueDefault] é o default do
     * core-engine, usado como "valor anterior" só quando esta é a 1ª mudança de [key] (sem override anterior).
     * `pg_advisory_xact_lock` serializa duas chamadas concorrentes para a MESMA [key] (lê o valor anterior e grava
     * o novo como uma única operação logicamente atômica, mesmo sem `UPDATE`/`FOR UPDATE` numa tabela append-only).
     * Idempotente por (chave, operacao_id): repetir não grava de novo.
     */
    private fun writeConfig(c: Connection, key: String, newValue: Long, previousValueDefault: Long, reason: String, operationId: String): Boolean {
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { st ->
            st.setString(1, key)
            st.executeQuery().use { it.next() }
        }
        val previous = latestConfig(c, key)?.first ?: previousValueDefault
        return c.prepareStatement(
            "INSERT INTO config_loja (chave, valor, valor_anterior, motivo, operacao_id) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (chave, operacao_id) DO NOTHING",
        ).use { st ->
            st.setString(1, key)
            st.setLong(2, newValue)
            st.setLong(3, previous)
            st.setString(4, reason)
            st.setString(5, operationId)
            st.executeUpdate() == 1
        }
    }

    private fun requireAccountExists(c: Connection, accountId: String) {
        c.prepareStatement("SELECT 1 FROM conta WHERE id = ?").use { st ->
            st.setString(1, accountId)
            st.executeQuery().use { rs -> if (!rs.next()) throw EconomyException(EconomyException.Code.CONTA_INEXISTENTE, "Conta não encontrada.") }
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
     * O `CHECK` do banco recusa saldo ou quantidade negativos (a transação inteira volta). [detail] é o texto livre
     * do operador (§18.7 "ajustar saldo ou booster"); `null` em todo lançamento automático (nunca usado antes de AJUSTE).
     */
    private fun apply(
        c: Connection, accountId: String, reason: String, key: String,
        coins: Long = 0, boosterId: String? = null, quantity: Int = 0, reference: String? = null, detail: String? = null,
    ): Boolean {
        val inserted = c.prepareStatement(
            "INSERT INTO lancamento (conta_id, motivo, moedas, booster_id, quantidade, referencia, detalhe, chave) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (conta_id, chave) DO NOTHING",
        ).use {
            it.setString(1, accountId)
            it.setString(2, reason)
            it.setLong(3, coins)
            it.setString(4, boosterId)
            it.setInt(5, quantity)
            it.setString(6, reference)
            it.setString(7, detail)
            it.setString(8, key)
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
