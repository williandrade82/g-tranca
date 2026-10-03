package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import kotlin.random.Random

/**
 * Determinização: sorteia um [RoundState] completo e coerente com esta vista, preenchendo ao acaso as
 * cartas ocultas. É a base de simulação do bot Difícil (ISMCTS), que só recebe a [PlayerView].
 *
 * Fica igual ao que o assento vê: a própria mão (na mesma ordem), o lixo (§5.6, na mesma ordem), as mesas,
 * os 3 vermelhos, a situação e o tamanho de cada morto, o tamanho do monte e das mãos, a fase, a vez,
 * o [PlayerView.firstSeat], o modo e o resultado. Portanto `determinize(r).viewFor(seat) == this`, e as
 * ações válidas do [PlayerView.seat] no estado sorteado são as mesmas do estado real.
 *
 * As cartas ocultas (as 104 de [Deck.standard] menos as visíveis) vão para as mãos alheias (inclusive a do
 * parceiro), o monte (§3.3) e os mortos disponíveis (§3.2), uniformemente entre as distribuições coerentes:
 * - as cartas de [known] vão obrigatoriamente para a mão do assento indicado;
 * - §6.5 3 vermelho nunca fica na mão (é baixado assim que entra), então os 3 vermelhos ocultos só caem no
 *   monte ou nos mortos;
 * - §10/§11.2 se o assento da vez ainda vai comprar ([Phase.AWAITING_DRAW]) e não há monte nem morto
 *   disponível, ele tem de poder pegar o lixo (senão a partida já teria terminado sem vencedor). O sorteio é
 *   então condicionado por amostragem com rejeição: sorteia-se como acima e descarta-se a distribuição em que
 *   o assento da vez não pode pegar o lixo ([LegalActions.canTakeDiscardPile]), por até
 *   [MAX_DETERMINIZATION_ATTEMPTS] tentativas. A rejeição não enviesa: a distribuição aceita é uniforme entre
 *   as que cumprem a condição. Se nenhuma tentativa servir, a vista é tratada como incoerente; com
 *   probabilidade de aceitação `p`, a chance de recusar indevidamente uma vista real é
 *   `(1 - p)^MAX_DETERMINIZATION_ATTEMPTS`. Isso não afeta o bot Difícil, que só busca na própria vez: aí a
 *   mão do assento da vez é a própria, conhecida, e a condição é conferida uma única vez, sem sorteio.
 *
 * A ordem do monte (índice 0 = topo) e dos mortos também é sorteada. A ordem das mãos alheias não tem
 * significado: primeiro as cartas de [known], depois as sorteadas.
 *
 * Usa apenas o [random] recebido: a mesma semente gera o mesmo estado.
 *
 * @param known cartas que o assento sabe estarem na mão de outro assento (ex.: as cartas do lixo que ele
 *   levou ao pegá-lo, §5.2/§5.6). Cada carta é uma cópia física específica ([Card.deck]).
 * @throws IllegalArgumentException se a vista for incoerente (lado que não é o do assento, cartas visíveis
 *   repetidas, contagens que não somam 104, 3 vermelho na própria mão, no lixo ou num conjunto da mesa, carta
 *   que não é 3 vermelho na área de 3 vermelhos, conjunto da mesa diferente do que [Meld.create] produz com
 *   as mesmas cartas, morto indisponível com cartas, 3 vermelho oculto sem lugar fora das mãos, assento da
 *   vez sem monte nem morto que não pode pegar o lixo) ou se [known] for incoerente (assento fora do modo ou o próprio, carta visível, carta repetida,
 *   3 vermelho, mais cartas do que o tamanho da mão).
 */
fun PlayerView.determinize(random: Random, known: Map<Seat, List<Card>> = emptyMap()): RoundState {
    val hidden = hiddenCardsOrThrow()
    val knownBySeat = validateKnown(known, hidden)

    // §6.5 3 vermelhos ocultos estão necessariamente no monte ou num morto
    val knownCards = knownBySeat.values.flatten().toSet()
    val free = hidden.filterNot { it in knownCards }
    val freeRedThrees = free.filter { it.isRedThree }
    val freeOthers = free.filterNot { it.isRedThree }
    val outsideHands = stockSize + mortoSizes.sum()
    require(freeRedThrees.size <= outsideHands) {
        "Vista incoerente: ${freeRedThrees.size} 3 vermelho(s) oculto(s), mas só $outsideHands lugar(es) " +
            "fora das mãos (monte e mortos); 3 vermelho nunca fica na mão (§6.5)"
    }

    // §10/§11.2 sem monte nem morto, o assento da vez que vai comprar tem de poder pegar o lixo
    val mustTakeDiscardPile = phase == Phase.AWAITING_DRAW && stockSize == 0 &&
        mortoStatus.none { it == MortoStatus.Available }
    if (!mustTakeDiscardPile) return sample(random, knownBySeat, freeOthers, freeRedThrees)

    val top = discardTop
    require(top != null && !top.isBlackThree) {
        "Vista incoerente: sem monte nem morto, o assento ${currentSeat.index} da vez não pode pegar o lixo " +
            "(${if (top == null) "vazio, §5.5" else "travado por 3 preto, §5.3"}); a partida já teria terminado " +
            "sem vencedor (§10, §11.2)"
    }
    // na vez do próprio assento a mão da vez é a própria, fixa: basta conferir uma vez
    val attempts = if (currentSeat == seat) 1 else MAX_DETERMINIZATION_ATTEMPTS
    repeat(attempts) {
        val sampled = sample(random, knownBySeat, freeOthers, freeRedThrees)
        if (LegalActions.canTakeDiscardPile(sampled, currentSeat, RuleSet.DEFAULT)) return sampled
    }
    throw IllegalArgumentException(
        "Vista incoerente: sem monte nem morto, o assento ${currentSeat.index} da vez não pode pegar o lixo " +
            "em nenhuma de $attempts distribuição(ões) sorteada(s); a partida já teria terminado sem vencedor " +
            "(§10, §11.2)",
    )
}

/**
 * Tentativas da amostragem com rejeição de [determinize] quando o assento da vez, sem monte nem morto, tem de
 * poder pegar o lixo (§10/§11.2).
 */
internal const val MAX_DETERMINIZATION_ATTEMPTS: Int = 1_000

/** Uma distribuição uniforme das cartas ocultas livres entre as vagas (ver [determinize]). */
private fun PlayerView.sample(
    random: Random,
    knownBySeat: Map<Seat, List<Card>>,
    freeOthers: List<Card>,
    freeRedThrees: List<Card>,
): RoundState {
    // vagas livres de cada mão alheia (descontadas as cartas conhecidas)
    val otherSeats = mode.seats.filter { it != seat }
    val openSlots = otherSeats.associateWith { handSizes[it.index] - knownBySeat[it].orEmpty().size }

    // Uniforme entre as distribuições coerentes: as cartas comuns, embaralhadas, enchem primeiro as vagas das
    // mãos; as que sobram, junto com os 3 vermelhos, são embaralhadas de novo para o monte e os mortos.
    val shuffledOthers = freeOthers.shuffled(random)
    val handSlotCount = openSlots.values.sum()
    val toHands = shuffledOthers.take(handSlotCount)
    val outside = (shuffledOthers.drop(handSlotCount) + freeRedThrees).shuffled(random)

    val hands = MutableList(mode.seatCount) { emptyList<Card>() }
    hands[seat.index] = hand.toList()
    var next = 0
    for (other in otherSeats) {
        val slots = openSlots.getValue(other)
        hands[other.index] = knownBySeat[other].orEmpty() + toHands.subList(next, next + slots)
        next += slots
    }
    val stock = outside.subList(0, stockSize).toList()
    var offset = stockSize
    val mortos = mortoSizes.map { size -> outside.subList(offset, offset + size).toList().also { offset += size } }

    return RoundState(
        mode = mode,
        hands = hands.toList(),
        stock = stock,
        discardPile = discardPile.toList(),
        mortos = mortos,
        redThrees = redThrees.map { it.toList() },
        tables = tables.map { it.defensiveCopy() },
        firstSeat = firstSeat,
        currentSeat = currentSeat,
        phase = phase,
        mortoStatus = mortoStatus.toList(),
        result = result,
    )
}

/** Cartas ocultas a este assento, conferindo a coerência da vista. */
private fun PlayerView.hiddenCardsOrThrow(): List<Card> {
    require(handSizes.size == mode.seatCount) {
        "Vista incoerente: ${handSizes.size} tamanhos de mão para ${mode.seatCount} assentos"
    }
    mode.requireSeat(seat)
    require(side == mode.sideOf(seat)) {
        "Vista incoerente: lado ${side.index} informado, mas o assento ${seat.index} é do lado ${mode.sideOf(seat).index} (§1.1)"
    }
    require(redThrees.size == mode.sideCount && tables.size == mode.sideCount) {
        "Vista incoerente: esperados ${mode.sideCount} lados de 3 vermelhos e de mesa"
    }
    require(handSizes.all { it >= 0 } && stockSize >= 0 && mortoSizes.all { it >= 0 }) {
        "Vista incoerente: tamanho negativo"
    }
    require(hand.size == handSizes[seat.index]) {
        "Vista incoerente: a própria mão tem ${hand.size} cartas, mas o tamanho informado é ${handSizes[seat.index]}"
    }
    require(hand.none { it.isRedThree }) { "Vista incoerente: 3 vermelho na própria mão (§6.5 é baixado na hora)" }
    require(mortoSizes.size == mortoStatus.size) { "Vista incoerente: ${mortoSizes.size} tamanhos para ${mortoStatus.size} mortos" }
    mortoStatus.forEachIndexed { i, status ->
        require(status == MortoStatus.Available || mortoSizes[i] == 0) {
            "Vista incoerente: morto $i está $status mas tem ${mortoSizes[i]} cartas (§9.1, §10.1)"
        }
    }

    validateTableCards()

    val visible = hand + discardPile + redThrees.flatten() + tables.flatMap { it.allCards() }
    val repeated = visible.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(repeated.isEmpty()) { "Vista incoerente: carta visível repetida: $repeated" }
    val visibleSet = visible.toSet()
    val hidden = Deck.standard().filterNot { it in visibleSet }
    val hiddenSlots = handSizes.sum() - hand.size + stockSize + mortoSizes.sum()
    require(hidden.size == hiddenSlots) {
        "Vista incoerente: ${visible.size} cartas visíveis + $hiddenSlots ocultas (mãos alheias, monte, mortos) " +
            "não somam ${Deck.SIZE}"
    }
    return hidden
}

/** §6.5 3 vermelhos só na área de 3 vermelhos; §6 só conjuntos válidos na mesa. */
private fun PlayerView.validateTableCards() {
    val notRed = redThrees.flatten().filterNot { it.isRedThree }
    require(notRed.isEmpty()) { "Vista incoerente: $notRed na área de 3 vermelhos não é 3 vermelho (§6.5)" }
    val redInDiscard = discardPile.filter { it.isRedThree }
    require(redInDiscard.isEmpty()) {
        "Vista incoerente: 3 vermelho no lixo ($redInDiscard); ele é baixado na hora e não pode ser descartado (§6.5)"
    }
    tables.forEachIndexed { sideIndex, table ->
        val redOnTable = table.allCards().filter { it.isRedThree }
        require(redOnTable.isEmpty()) {
            "Vista incoerente: 3 vermelho na mesa do lado $sideIndex num conjunto ($redOnTable); nenhum 3 compõe " +
                "conjunto e o 3 vermelho vai para a área de 3 vermelhos (§6.2, §6.5)"
        }
        for (tableMeld in table.melds) {
            val meld = tableMeld.meld
            // a mesma validação do motor: o conjunto tem de ser exatamente o que Meld.create produz com as cartas
            val created = Meld.create(meld.cards, RuleSet.DEFAULT)
            require(created is RuleResult.Ok && created.value == meld) {
                val reason = when (created) {
                    is RuleResult.Failure -> created.error.toString()
                    is RuleResult.Ok -> "as cartas formam ${created.value.kind}"
                }
                "Vista incoerente: conjunto inválido na mesa do lado $sideIndex (id ${tableMeld.id.value}: " +
                    "${meld.kind} ${meld.cards}): $reason (§6)"
            }
        }
    }
}

/** Confere as cartas conhecidas e devolve só os assentos com alguma carta. */
private fun PlayerView.validateKnown(known: Map<Seat, List<Card>>, hidden: List<Card>): Map<Seat, List<Card>> {
    val hiddenSet = hidden.toSet()
    val all = known.values.flatten()
    val repeated = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(repeated.isEmpty()) {
        "Cartas conhecidas incoerentes: carta repetida $repeated (cada cópia física está num só lugar, §1)"
    }
    for ((owner, cards) in known) {
        if (cards.isEmpty()) continue
        mode.requireSeat(owner)
        require(owner != seat) { "Cartas conhecidas incoerentes: a mão do próprio assento já é visível" }
        val visible = cards.filterNot { it in hiddenSet }
        require(visible.isEmpty()) { "Cartas conhecidas incoerentes: $visible já está(ão) visível(is) ao assento" }
        val redThrees = cards.filter { it.isRedThree }
        require(redThrees.isEmpty()) {
            "Cartas conhecidas incoerentes: $redThrees na mão do assento ${owner.index}, mas 3 vermelho nunca fica na mão (§6.5)"
        }
        require(cards.size <= handSizes[owner.index]) {
            "Cartas conhecidas incoerentes: ${cards.size} cartas para o assento ${owner.index}, " +
                "acima do tamanho da mão (${handSizes[owner.index]})"
        }
    }
    return known.filterValues { it.isNotEmpty() }
}
