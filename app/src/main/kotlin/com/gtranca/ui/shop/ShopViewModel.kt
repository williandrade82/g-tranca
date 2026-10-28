package com.gtranca.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.ShopRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Shop
import com.gtranca.engine.ShopOffer
import com.gtranca.game.WriteQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Uma oferta da loja com o booster do catálogo que ela vende (§17.10). */
data class ShopItem(val offer: ShopOffer, val def: BoosterDef) {
    /** Saldo depois da compra; negativo = não cobre. */
    fun balanceAfter(balance: Int): Int = Shop.balanceAfter(balance, offer)
}

/** Aviso da loja: a compra não passou (saldo) ou falhou (disco). */
enum class ShopNotice { INSUFFICIENT, FAILED }

data class ShopUi(
    /** Saldo de moedas; `null` enquanto lê. */
    val balance: Int? = null,
    val items: List<ShopItem> = emptyList(),
    /** A oferta do modal *Comprar booster* (docs/telas.md 4); `null` = modal fechado. */
    val selected: ShopItem? = null,
    val buying: Boolean = false,
    val notice: ShopNotice? = null,
    /** A última compra concluída, para o aviso "Compra feita". */
    val bought: ShopItem? = null,
) {
    /** §17.10 a compra só vale com saldo que cobre o valor (pode zerar, nunca negativar). */
    val canBuy: Boolean get() = selected != null && balance != null && selected.balanceAfter(balance) >= 0 && !buying
}

/** §17.10 Loja: lista as ofertas, abre o modal de compra e compra pelo [ShopRepository] (na fila de gravações). */
class ShopViewModel(
    wallet: WalletRepository,
    private val shop: ShopRepository,
    catalog: List<BoosterDef>,
    private val writes: WriteQueue,
    offers: List<ShopOffer> = Shop.OFFERS,
) : ViewModel() {
    private val items = offers.mapNotNull { offer -> catalog.firstOrNull { it.id == offer.boosterId }?.let { ShopItem(offer, it) } }
    private val selected = MutableStateFlow<ShopItem?>(null)
    private val buying = MutableStateFlow(false)
    private val notice = MutableStateFlow<ShopNotice?>(null)
    private val bought = MutableStateFlow<ShopItem?>(null)
    private val balance: StateFlow<Int?> = wallet.wallet.map<com.gtranca.engine.Wallet, Int?> { it.balance }.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ShopUi> = combine(
        balance,
        selected,
        buying,
        notice,
        bought,
    ) { balance, selected, buying, notice, bought ->
        ShopUi(balance, items, selected, buying, notice, bought)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShopUi(items = items))

    init {
        // Uma compra interrompida (o app caiu depois do débito) termina ao abrir a loja.
        viewModelScope.launch { writes.run { shop.recoverPending() } }
    }

    /** Toque numa oferta: abre o modal *Comprar booster*. */
    fun onOffer(item: ShopItem) {
        notice.value = null
        bought.value = null
        selected.value = item
    }

    fun onCancel() {
        if (!buying.value) selected.value = null
    }

    /** Confirma a compra aberta no modal; sem saldo, ou com uma compra em andamento, não faz nada. */
    fun onBuy() {
        val item = selected.value ?: return
        val current = balance.value ?: return
        if (buying.value || item.balanceAfter(current) < 0) return
        buying.value = true
        viewModelScope.launch {
            var done = false
            val ok = writes.run { done = shop.purchase(item.offer) }
            buying.value = false
            when {
                !ok -> notice.value = ShopNotice.FAILED
                !done -> notice.value = ShopNotice.INSUFFICIENT
                else -> {
                    notice.value = null
                    bought.value = item
                    selected.value = null
                }
            }
        }
    }
}
