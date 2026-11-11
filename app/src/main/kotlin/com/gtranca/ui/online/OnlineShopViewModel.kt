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
import com.gtranca.online.contracts.ShopConfigDto
import com.gtranca.online.contracts.ShopOfferDto
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
 * [problem] é o aviso de rede/servidor fora na própria tela — nunca fecha a loja (§17.10/§18.5). [staleOffers] avisa
 * que o preço/quantidade mostrados são o padrão do `:core-engine` porque a configuração do servidor ([EconomyApi.shopConfig])
 * não pôde ser lida agora: a COMPRA em si nunca cobra esse valor desatualizado (o servidor sempre decide pelo preço
 * atual dentro da própria transação, §18.7) — isto é só para o jogador não confiar demais no número mostrado.
 */
data class OnlineShopUi(
    val status: OnlineShopStatus = OnlineShopStatus.LOADING,
    val problem: OnlineProblem? = null,
    val shop: ShopUi = ShopUi(),
    val staleOffers: Boolean = false,
)

/**
 * §17.10/§18.5/§18.7 Loja online (docs/telas.md 3.20a): as nove ofertas com o preço/quantidade ATUAL do servidor
 * ([EconomyApi.shopConfig], nunca `Shop.OFFERS` isolado do app — ver `docs/seguranca-pendencias.md`, 2026-11-08),
 * lendo e gravando a carteira e o inventário ONLINE pela [economy] (nunca os do aparelho). Falha ao buscar a
 * configuração não derruba a tela: mostra o padrão do `:core-engine` com [OnlineShopUi.staleOffers] — a compra
 * continua correta porque o servidor sempre cobra pelo preço de verdade dentro da própria transação.
 *
 * A chave de compra ([newKey]) é gerada **uma vez por intenção de compra** e **repetida** se a tentativa falhar por
 * rede/servidor fora: nunca gera uma chave nova para a mesma intenção, senão a compra seria cobrada duas vezes (o
 * livro-razão do servidor só evita a repetição com a MESMA chave, ver [EconomyApi.buyOffer]). Enquanto uma compra
 * assim fica sem resposta definitiva, nenhuma outra oferta abre — só *Tentar de novo* resolve a pendente.
 */
class OnlineShopViewModel(
    private val account: OnlineAccount,
    private val economy: EconomyApi,
    private val catalog: List<BoosterDef>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineShopUi(shop = ShopUi(items = buildItems(defaultOffers()))))
    val uiState: StateFlow<OnlineShopUi> = _uiState.asStateFlow()

    /** Compra pendente (item + chave) enquanto a falha de rede/servidor não foi resolvida; [retry] repete com a
     * MESMA chave (nunca outra, senão cobraria duas vezes). */
    private var pendingBuy: Pair<ShopItem, String>? = null

    init { load() }

    /** *Tentar de novo*: repete a compra pendente com a mesma chave, ou relê a carteira e a configuração se a falha
     * foi ao abrir a loja. */
    fun retry() {
        _uiState.update { it.copy(problem = null) }
        val pending = pendingBuy
        if (pending != null) doBuy(pending.first, pending.second) else load()
    }

    private fun load() {
        _uiState.update { it.copy(status = OnlineShopStatus.LOADING, problem = null) }
        viewModelScope.launch {
            runCatching { account.callWith(economy) { api, guest -> api.economyState(guest.token, guest.id) } }.fold(
                onSuccess = { dto ->
                    // §17.10/§18.7: busca o preço/quantidade ATUAL antes de publicar READY — nunca mostra o padrão
                    // fixo por um instante e troca depois (a mesma divergência que este trabalho corrige).
                    val (items, stale) = loadShopConfig()
                    _uiState.update {
                        it.copy(status = OnlineShopStatus.READY, staleOffers = stale, shop = it.shop.copy(balance = dto.balance.toShopBalance(), items = items))
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(status = OnlineShopStatus.ERROR, problem = e.economyProblem()) } },
            )
        }
    }

    /**
     * §17.10/§18.7: preço/quantidade atuais. Uma falha aqui (rede, servidor fora, ou uma resposta que não bate com
     * o padrão esperado) nunca tranca a tela: cai no padrão fixo do `:core-engine` com [OnlineShopUi.staleOffers] —
     * a compra em si é sempre validada pelo servidor, então o pior caso é mostrar o preço errado, nunca cobrar errado.
     */
    private suspend fun loadShopConfig(): Pair<List<ShopItem>, Boolean> {
        val config = runCatching { economy.shopConfig() }.getOrNull()
        val (offers, stale) = offersOf(config)
        return buildItems(offers) to stale
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
            // §18.7: o índice vem do próprio item (o do servidor, ShopOfferDto.index), NUNCA de offers.indexOf(item.offer) —
            // um override do administrador pode deixar duas ofertas iguais em (boosterId, quantity, price).
            runCatching { account.callWith(economy) { api, guest -> api.buyOffer(guest.token, guest.id, item.offerIndex, key) } }.fold(
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

    /** As nove ofertas do padrão fixo do `:core-engine` (mesma ordem/índice de `Shop.OFFERS`), para antes da 1ª
     * resposta do servidor e para o retorno (fallback) de uma falha ao buscar [ShopConfigDto]. */
    private fun defaultOffers(): List<ShopOfferDto> =
        Shop.OFFERS.mapIndexed { index, offer -> ShopOfferDto(index, offer.boosterId, offer.price.toLong(), offer.quantity.toLong()) }

    /**
     * As ofertas de [config] ([staleOffers][OnlineShopUi.staleOffers] = `true` se [config] for `null` — a busca
     * falhou — OU se qualquer posição não bater com o esperado: índice fora de ordem, `boosterId` trocado,
     * preço/quantidade zerados ou negativos (nunca esperado do servidor, mas um JSON corrompido ou uma versão
     * incompatível não pode derrubar a tela) — essa posição usa o padrão fixo do `:core-engine`.
     */
    private fun offersOf(config: ShopConfigDto?): Pair<List<ShopOfferDto>, Boolean> {
        if (config == null) return defaultOffers() to true
        var stale = false
        val offers = defaultOffers().mapIndexed { index, fallback ->
            val dto = config.offers.getOrNull(index)
            if (dto != null && dto.index == index && dto.boosterId == fallback.boosterId &&
                dto.price in 1..Int.MAX_VALUE.toLong() && dto.quantity in 1..Int.MAX_VALUE.toLong()
            ) {
                dto
            } else {
                stale = true
                fallback
            }
        }
        return offers to stale
    }

    /** Junta cada oferta com o booster do catálogo que ela vende (uma oferta sem booster correspondente não aparece —
     * nunca deveria faltar, mas não é motivo para quebrar a tela). */
    private fun buildItems(offers: List<ShopOfferDto>): List<ShopItem> = offers.mapNotNull { dto ->
        val def = catalog.firstOrNull { it.id == dto.boosterId } ?: return@mapNotNull null
        ShopItem(ShopOffer(dto.boosterId, dto.quantity.toInt(), dto.price.toInt()), def, dto.index)
    }
}

/** [com.gtranca.online.contracts.EconomyStateDto.balance] é `Long`; o painel da loja usa `Int` (como a carteira local). Nenhum
 * saldo real deste jogo chega perto do limite de um `Int`. */
private fun Long.toShopBalance(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun Throwable.economyProblem(): OnlineProblem = (this as? OnlineException)?.toEconomyProblem() ?: OnlineProblem.UNEXPECTED
