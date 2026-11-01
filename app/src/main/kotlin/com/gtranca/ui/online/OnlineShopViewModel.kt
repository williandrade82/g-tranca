package com.gtranca.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Shop
import com.gtranca.engine.ShopOffer
import com.gtranca.online.client.EconomyApi
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.ui.shop.ShopItem
import com.gtranca.ui.shop.ShopNotice
import com.gtranca.ui.shop.ShopUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OnlineShopStatus { LOADING, READY, ERROR }

/**
 * Estado da Loja online (docs/telas.md 3.20a). [shop] é o mesmo painel/modal da Loja (3.20, [com.gtranca.ui.shop.ShopUi]);
 * [problem] é o aviso de rede/servidor fora na própria tela — nunca fecha a loja (§17.10/§18.5).
 */
data class OnlineShopUi(
    val status: OnlineShopStatus = OnlineShopStatus.LOADING,
    val problem: OnlineProblem? = null,
    val shop: ShopUi = ShopUi(),
)

/**
 * §17.10/§18.5 Loja online (docs/telas.md 3.20a): as mesmas nove ofertas de [offers] (= [Shop.OFFERS], o índice que o
 * `economy-service` usa em `Shop.OFFERS.getOrNull(offerIndex)` — mesma fonte, nenhuma lista espelhada à mão), lendo e
 * gravando a carteira e o inventário ONLINE pela [economy] (nunca os do aparelho).
 *
 * A chave de compra ([newKey]) é gerada **uma vez por intenção de compra** e **repetida** se a tentativa falhar por
 * rede/servidor fora: nunca gera uma chave nova para a mesma intenção, senão a compra seria cobrada duas vezes (o
 * livro-razão do servidor só evita a repetição com a MESMA chave, ver [EconomyApi.buyOffer]). Enquanto uma compra
 * assim fica sem resposta definitiva, nenhuma outra oferta abre — só *Tentar de novo* resolve a pendente.
 */
class OnlineShopViewModel(
    private val account: OnlineAccount,
    private val economy: EconomyApi,
    catalog: List<BoosterDef>,
    private val offers: List<ShopOffer> = Shop.OFFERS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val items = offers.mapNotNull { offer -> catalog.firstOrNull { it.id == offer.boosterId }?.let { ShopItem(offer, it) } }
    private val _uiState = MutableStateFlow(OnlineShopUi(shop = ShopUi(items = items)))
    val uiState: StateFlow<OnlineShopUi> = _uiState.asStateFlow()

    /** Compra pendente (item + chave) enquanto a falha de rede/servidor não foi resolvida; [retry] repete com a
     * MESMA chave (nunca outra, senão cobraria duas vezes). */
    private var pendingBuy: Pair<ShopItem, String>? = null

    init { load() }

    /** *Tentar de novo*: repete a compra pendente com a mesma chave, ou relê a carteira se a falha foi ao abrir a loja. */
    fun retry() {
        _uiState.update { it.copy(problem = null) }
        val pending = pendingBuy
        if (pending != null) doBuy(pending.first, pending.second) else load()
    }

    private fun load() {
        _uiState.update { it.copy(status = OnlineShopStatus.LOADING, problem = null) }
        viewModelScope.launch {
            runCatching { account.callWith(economy) { api, guest -> api.economyState(guest.token, guest.id) } }.fold(
                onSuccess = { dto -> _uiState.update { it.copy(status = OnlineShopStatus.READY, shop = it.shop.copy(balance = dto.balance.toShopBalance())) } },
                onFailure = { e -> _uiState.update { it.copy(status = OnlineShopStatus.ERROR, problem = e.economyProblem()) } },
            )
        }
    }

    /** Relê só o saldo (sem passar por LOADING): usado depois de um `SALDO_INSUFICIENTE` do servidor, para corrigir
     * um saldo que ficou desatualizado na tela (ex.: outro aparelho comprou primeiro). */
    private fun refreshBalance() {
        viewModelScope.launch {
            runCatching { account.callWith(economy) { api, guest -> api.economyState(guest.token, guest.id) } }.onSuccess { dto ->
                _uiState.update { it.copy(shop = it.shop.copy(balance = dto.balance.toShopBalance())) }
            }
        }
    }

    /** Toque numa oferta: abre o modal. Ignorado enquanto uma compra anterior está sem resposta definitiva. */
    fun onOffer(item: ShopItem) {
        if (pendingBuy != null) return
        _uiState.update { it.copy(problem = null, shop = it.shop.copy(selected = item, notice = null, bought = null)) }
    }

    fun onCancel() {
        if (_uiState.value.shop.buying) return
        _uiState.update { it.copy(shop = it.shop.copy(selected = null)) }
    }

    fun onBuy() {
        val ui = _uiState.value
        val item = ui.shop.selected ?: return
        if (ui.shop.buying) return
        val key = pendingBuy?.takeIf { it.first == item }?.second ?: newKey()
        doBuy(item, key)
    }

    private fun doBuy(item: ShopItem, key: String) {
        pendingBuy = item to key
        _uiState.update { it.copy(problem = null, shop = it.shop.copy(selected = item, buying = true, notice = null)) }
        viewModelScope.launch {
            runCatching { account.callWith(economy) { api, guest -> api.buyOffer(guest.token, guest.id, offers.indexOf(item.offer), key) } }.fold(
                onSuccess = { dto ->
                    pendingBuy = null
                    _uiState.update {
                        it.copy(shop = it.shop.copy(buying = false, selected = null, bought = item, notice = null, balance = dto.balance.toShopBalance()))
                    }
                },
                onFailure = { e -> onBuyFailed(e) },
            )
        }
    }

    /**
     * A chave só pode ser esquecida quando a resposta é DEFINITIVA (o servidor respondeu com um código de negócio —
     * [OnlineErrorKind.API] — então o pedido chegou e foi recusado, nenhuma compra foi feita). Rede, manutenção ou
     * qualquer resposta sem corpo reconhecível ([OnlineErrorKind.UNEXPECTED]/`UNAUTHENTICATED`/`MAINTENANCE`/`NETWORK`)
     * não dizem se o servidor já processou o pedido antes de a conexão falhar — aí a chave fica, para a *mesma*
     * tentativa se repetir sem cobrar duas vezes.
     */
    private fun onBuyFailed(e: Throwable) {
        val oe = e as? OnlineException
        if (oe?.kind == OnlineErrorKind.API) {
            pendingBuy = null
            val insufficient = oe.economyCode == EconomyErrorCode.SALDO_INSUFICIENTE
            _uiState.update { it.copy(shop = it.shop.copy(buying = false, notice = if (insufficient) ShopNotice.INSUFFICIENT else ShopNotice.FAILED)) }
            // §17.10 a tela já mostra "Saldo insuficiente" pelo `canBuy`; relê para corrigir um saldo desatualizado.
            if (insufficient) refreshBalance()
        } else {
            // Mantém a chave; fecha só o modal e avisa na própria tela — a loja continua aberta (não fecha sozinha).
            _uiState.update { it.copy(shop = it.shop.copy(buying = false, selected = null), problem = oe?.economyProblem() ?: OnlineProblem.UNEXPECTED) }
        }
    }
}

/** [com.gtranca.online.contracts.EconomyStateDto.balance] é `Long`; o painel da loja usa `Int` (como a carteira local). Nenhum
 * saldo real deste jogo chega perto do limite de um `Int`. */
private fun Long.toShopBalance(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun Throwable.economyProblem(): OnlineProblem = (this as? OnlineException)?.toEconomyProblem() ?: OnlineProblem.UNEXPECTED
