package com.gtranca.engine

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * §17.1 o tipo do booster: [SKILL] aumenta a percepção do jogador sobre uma jogada (a mesa pode proibi-lo, §17.9);
 * [INTERACTION] permite interagir com os outros jogadores.
 */
@Serializable
enum class BoosterKind { SKILL, INTERACTION }

/** §17.1 um booster do catálogo. Os mesmos campos do JSON local do catálogo. */
@Serializable
data class BoosterDef(
    val id: String,
    val name: String,
    val summary: String,
    /** Custo de cada partida em que o booster é escolhido (§17.3); padrão 1. */
    val cost: Int = 1,
    val initialQuantity: Int,
    /** Faixa da quantidade sorteada no presente diário (§17.5). */
    val giftMin: Int,
    val giftMax: Int,
    /** §17.1 Skill ou Interação (padrão Skill). */
    val kind: BoosterKind = BoosterKind.SKILL,
)

/** §17.1 o catálogo inicial. */
object BoosterCatalog {
    const val GREEN_LED = "b1"
    const val RED_LED = "b2"
    const val REACTIONS = "b3"

    /** §17.1 o booster [id] é de skill? (id fora do catálogo: não). */
    fun isSkill(id: String): Boolean = DEFAULT.firstOrNull { it.id == id }?.kind == BoosterKind.SKILL

    val DEFAULT: List<BoosterDef> = listOf(
        BoosterDef(GREEN_LED, "Green-LED", "Mostra um ponto verde nas cartas que pode baixar", 1, 5, 1, 3),
        BoosterDef(RED_LED, "Yellow-LED", "Mostra um ponto amarelo nas cartas que se encaixam no jogo do adversário", 1, 5, 1, 3),
        BoosterDef(REACTIONS, "Reações", "Permite expressar até 3 reações durante a partida", 1, 5, 2, 7, BoosterKind.INTERACTION),
    )
}

/**
 * §17.1 inventário do jogador: quantidade de cada booster (as moedas ficam na carteira, §14.6). Imutável; id fora do
 * mapa vale 0.
 */
@Serializable
data class Inventory(val quantities: Map<String, Int> = emptyMap()) {
    init {
        require(quantities.values.all { it >= 0 }) { "Quantidade não pode ser negativa" }
    }

    fun quantity(id: String): Int = quantities[id] ?: 0

    fun add(id: String, amount: Int): Inventory {
        require(amount >= 0) { "Quantidade a somar não pode ser negativa" }
        return Inventory(quantities + (id to quantity(id) + amount))
    }

    /** Debita [amount] de [id]; `null` se não há o bastante. */
    fun consume(id: String, amount: Int): Inventory? {
        require(amount >= 0) { "Quantidade a debitar não pode ser negativa" }
        val left = quantity(id) - amount
        return if (left < 0) null else Inventory(quantities + (id to left))
    }

    companion object {
        /** §17.1 quem ainda não tem inventário recebe a quantidade inicial de cada booster. */
        fun initial(catalog: List<BoosterDef> = BoosterCatalog.DEFAULT): Inventory =
            Inventory(catalog.associate { it.id to it.initialQuantity })
    }
}

/** §17.2, §17.3 a escolha de até 3 boosters distintos (nunca moedas: só entram ids do catálogo). */
object Loadout {
    const val MAX = 3

    /** Cabe escolher [id]: está no catálogo e há quantidade para o custo. */
    fun isSelectable(id: String, inventory: Inventory, catalog: List<BoosterDef>): Boolean =
        catalog.firstOrNull { it.id == id }?.let { inventory.quantity(id) >= it.cost } == true

    /** Tira repetidos, ids fora do catálogo (moedas) e os sem quantidade; mantém a ordem e no máximo [MAX]. */
    fun normalize(ids: List<String>, inventory: Inventory, catalog: List<BoosterDef>): List<String> =
        ids.distinct().filter { isSelectable(it, inventory, catalog) }.take(MAX)

    /** Marca/desmarca [id]; a marca que não cabe (quarta, sem quantidade, fora do catálogo) deixa tudo como está. */
    fun toggle(current: List<String>, id: String, inventory: Inventory, catalog: List<BoosterDef>): List<String> = when {
        id in current -> current - id
        current.size < MAX && isSelectable(id, inventory, catalog) -> current + id
        else -> current
    }

    /** O resultado de confirmar o seletor (§17.3): o que foi mesmo escolhido e o inventário já debitado. */
    data class Confirmed(val boosters: List<String>, val inventory: Inventory)

    /** §17.3 Seguir: debita o custo de cada escolhido; o que não puder ser debitado fica de fora. */
    fun confirm(selection: List<String>, inventory: Inventory, catalog: List<BoosterDef>): Confirmed {
        var left = inventory
        val chosen = mutableListOf<String>()
        for (id in normalize(selection, inventory, catalog)) {
            val cost = catalog.first { it.id == id }.cost
            left = left.consume(id, cost) ?: continue
            chosen += id
        }
        return Confirmed(chosen, left)
    }
}

/** §17.5 um presente: [quantity] unidades de [boosterId]. */
data class Gift(val boosterId: String, val quantity: Int)

/** §17.5 presente diário. Puro: o dia e o RNG chegam como parâmetros. */
object DailyGift {
    /** Chance de 3 em 5. */
    const val CHANCE_NUMERATOR = 3
    const val CHANCE_DENOMINATOR = 5

    /** Quem tem esta quantidade (ou mais) de um booster não o recebe por sorteio, e o total nunca passa dela. */
    const val MAX_STOCK = 10

    /** Há sorteio hoje: nunca sorteou, ou [today] é depois do último dia (relógio atrasado não sorteia de novo). */
    fun isDue(today: Long, lastDraw: Long?): Boolean = lastDraw == null || today > lastDraw

    /**
     * Um sorteio. Rola a chance sempre (a sequência do [rng] não depende do estoque); ganhando, sorteia o booster entre os
     * que o jogador tem menos de [MAX_STOCK] e a quantidade na faixa dele, limitada ao que falta para [MAX_STOCK].
     */
    fun draw(inventory: Inventory, catalog: List<BoosterDef>, rng: Random): Gift? {
        val won = rng.nextInt(CHANCE_DENOMINATOR) < CHANCE_NUMERATOR
        val eligible = catalog.filter { inventory.quantity(it.id) < MAX_STOCK }
        if (!won || eligible.isEmpty()) return null
        val def = eligible[rng.nextInt(eligible.size)]
        val room = MAX_STOCK - inventory.quantity(def.id)
        return Gift(def.id, rng.nextInt(def.giftMin, def.giftMax + 1).coerceAtMost(room))
    }
}
