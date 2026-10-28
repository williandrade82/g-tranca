package com.gtranca.data

import com.gtranca.engine.ShopOffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Uma compra que debitou a carteira e ainda não entrou no inventário (§17.10). */
data class PendingPurchase(val id: String, val boosterId: String, val quantity: Int)

/** O lado da carteira da compra (§17.10): o débito e o registro da compra pendente numa só gravação. */
interface ShopLedger {
    /** Debita [offer] se o saldo cobre (pode zerar) e deixa a compra [purchaseId] pendente; devolve se debitou. */
    suspend fun debitPurchase(purchaseId: String, offer: ShopOffer): Boolean

    /** A compra debitada que ainda não foi concluída, se houver. */
    suspend fun pendingPurchase(): PendingPurchase?

    /** Dá a compra [purchaseId] por concluída. */
    suspend fun clearPurchase(purchaseId: String)
}

/** O lado do inventário da compra (§17.10). */
interface ShopStock {
    /** Soma [quantity] de [boosterId]; a mesma [purchaseId] nunca soma duas vezes. */
    suspend fun applyPurchase(purchaseId: String, boosterId: String, quantity: Int)
}

/**
 * §17.10 Loja: a compra troca moedas por boosters. Saldo e inventário ficam em arquivos diferentes, então a compra é
 * feita em passos que podem ser retomados: debita e anota a compra pendente (um arquivo), soma ao inventário sem repetir
 * (outro arquivo) e dá a compra por concluída. Se o app cair no meio, [recoverPending] termina o que faltou, sem perder
 * moedas nem somar duas vezes.
 */
interface ShopRepository {
    /** Compra [offer]: `true` se comprou; `false` se o saldo não cobre (nada muda). */
    suspend fun purchase(offer: ShopOffer): Boolean

    /** Conclui uma compra interrompida; sem compra pendente não faz nada. */
    suspend fun recoverPending()
}

class DefaultShopRepository(
    private val ledger: ShopLedger,
    private val stock: ShopStock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ShopRepository {
    private val mutex = Mutex()

    override suspend fun purchase(offer: ShopOffer): Boolean = mutex.withLock {
        recover()
        val id = newId()
        if (!ledger.debitPurchase(id, offer)) return@withLock false
        stock.applyPurchase(id, offer.boosterId, offer.quantity)
        ledger.clearPurchase(id)
        true
    }

    override suspend fun recoverPending() = mutex.withLock { recover() }

    private suspend fun recover() {
        val pending = ledger.pendingPurchase() ?: return
        stock.applyPurchase(pending.id, pending.boosterId, pending.quantity)
        ledger.clearPurchase(pending.id)
    }
}
