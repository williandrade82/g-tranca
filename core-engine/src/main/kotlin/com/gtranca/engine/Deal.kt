package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.SideTable
import kotlin.random.Random

/**
 * Inicia uma partida: embaralha (§1) e distribui as cartas (§3), baixando os 3 vermelhos
 * recebidos (§3.5). O primeiro jogador é [firstSeat] ou, se `null` (1ª partida do jogo, §4.1),
 * sorteado com o mesmo [random].
 *
 * A mesma semente sempre produz o mesmo [RoundState].
 */
fun dealRound(
    mode: GameMode,
    random: Random,
    rules: RuleSet = RuleSet.DEFAULT,
    firstSeat: Seat? = null,
): RoundState {
    val deck = Deck.shuffled(random)
    val first = firstSeat ?: Seat(random.nextInt(mode.seatCount))
    return dealFromOrderedDeck(mode, deck, first, rules)
}

/**
 * Distribui a partir de um baralho já ordenado ([deck] índice 0 = primeira carta distribuída).
 *
 * 1. §3.1 [RuleSet.cardsPerHand] cartas a cada jogador, uma por vez, começando por [firstSeat]
 *    e seguindo o sentido horário (§4.2).
 * 2. §3.2 os mortos, com as cartas seguintes distribuídas uma por vez para cada morto,
 *    alternadamente, até cada um ter [RuleSet.cardsPerMorto] cartas.
 * 3. §3.3 o restante forma o monte, na mesma ordem (índice 0 = topo).
 * 4. §3.4 lixo vazio.
 * 5. §3.5 / §6.5 na ordem de jogada a partir de [firstSeat], cada 3 vermelho da mão vai para a mesa
 *    do lado do jogador e é reposto com a carta do topo do monte; se a reposição também for
 *    3 vermelho, repete (reposição em cadeia).
 */
internal fun dealFromOrderedDeck(
    mode: GameMode,
    deck: List<Card>,
    firstSeat: Seat,
    rules: RuleSet = RuleSet.DEFAULT,
): RoundState {
    require(deck.size == Deck.SIZE) { "Baralho deve ter ${Deck.SIZE} cartas, tem ${deck.size}" }
    require(deck.toSet().size == Deck.SIZE) { "Baralho com cartas repetidas" }
    mode.requireSeat(firstSeat)

    val playOrder = mode.seatsInPlayOrder(firstSeat)
    val handCards = rules.cardsPerHand * mode.seatCount
    val hands = List(mode.seatCount) { mutableListOf<Card>() }
    deck.subList(0, handCards).forEachIndexed { i, card ->
        hands[playOrder[i % mode.seatCount].index] += card
    }

    val mortoCards = rules.cardsPerMorto * rules.mortoCount
    val mortos = List(rules.mortoCount) { mutableListOf<Card>() }
    deck.subList(handCards, handCards + mortoCards).forEachIndexed { i, card ->
        mortos[i % rules.mortoCount] += card
    }
    val stock = ArrayDeque(deck.subList(handCards + mortoCards, deck.size))

    val redThrees = List(mode.sideCount) { mutableListOf<Card>() }
    for (seat in playOrder) {
        val hand = hands[seat.index]
        val side = mode.sideOf(seat)
        var redThree = hand.firstOrNull { it.isRedThree }
        while (redThree != null) {
            hand -= redThree
            redThrees[side.index] += redThree
            // §6.5 reposição do topo do monte. Na distribuição o monte nunca se esgota
            // (no máximo 4 reposições contra 38+ cartas), por isso §10 não se aplica aqui.
            check(stock.isNotEmpty()) { "Monte vazio durante a reposição de 3 vermelho da distribuição" }
            hand += stock.removeFirst()
            redThree = hand.firstOrNull { it.isRedThree }
        }
    }

    return RoundState(
        mode = mode,
        hands = hands.map { it.toList() },
        stock = stock.toList(),
        discardPile = emptyList(),
        mortos = mortos.map { it.toList() },
        redThrees = redThrees.map { it.toList() },
        tables = List(mode.sideCount) { SideTable() },
        firstSeat = firstSeat,
        currentSeat = firstSeat,
    )
}
