package com.gtranca.engine

/** §17.10 uma oferta da loja: [quantity] unidades do booster [boosterId] por [price] moedas (o total do pacote). */
data class ShopOffer(val boosterId: String, val quantity: Int, val price: Int) {
    init {
        require(quantity > 0) { "Quantidade da oferta deve ser positiva" }
        require(price > 0) { "Valor da oferta deve ser positivo" }
    }
}

/** O resultado de uma compra: a carteira e o inventário novos. */
data class ShopPurchase(val wallet: Wallet, val inventory: Inventory)

/** §17.10 Loja: ofertas fixas e a regra da compra (moedas por boosters). */
object Shop {
    /** As nove ofertas, na ordem da tela: b1, b2 e b3, cada um em pacotes de 10, 5 e 1. */
    val OFFERS: List<ShopOffer> = listOf(
        BoosterCatalog.GREEN_LED,
        BoosterCatalog.RED_LED,
        BoosterCatalog.REACTIONS,
    ).flatMap { id -> listOf(ShopOffer(id, 10, 50), ShopOffer(id, 5, 30), ShopOffer(id, 1, 10)) }

    /** Saldo depois da compra; negativo = a compra não cobre (a tela mostra "saldo insuficiente"). */
    fun balanceAfter(balance: Int, offer: ShopOffer): Int = balance - offer.price

    /**
     * §17.10 compra [offer]: debita o valor da [wallet] e soma a quantidade ao [inventory]. O saldo pode zerar, mas a compra
     * que o deixaria negativo não é feita (`null`).
     */
    fun purchase(wallet: Wallet, inventory: Inventory, offer: ShopOffer): ShopPurchase? {
        val debited = wallet.debit(offer.price) ?: return null
        return ShopPurchase(debited, inventory.add(offer.boosterId, offer.quantity))
    }
}
