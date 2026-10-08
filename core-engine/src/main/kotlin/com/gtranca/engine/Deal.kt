package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.SideTable
import kotlin.random.Random

/**
 * Inicia uma partida: embaralha (§1), distribui as cartas (§3) e começa a vez do primeiro jogador (§3.5): os 3 vermelhos
 * da mão dele são baixados e repostos antes da primeira compra. Os demais jogadores só trocam os seus na sua vez.
 * O primeiro jogador é [firstSeat] ou, se `null` (1ª partida do jogo, §4.1), sorteado com o mesmo [random].
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
 * Distribui a partir de um baralho já ordenado e começa a vez do primeiro jogador: [distributeOrderedDeck] seguida de
 * [RoundEngine.beginTurn] para [firstSeat] (§3.5: início da primeira vez, antes da compra).
 */
internal fun dealFromOrderedDeck(
    mode: GameMode,
    deck: List<Card>,
    firstSeat: Seat,
    rules: RuleSet = RuleSet.DEFAULT,
): RoundState = RoundEngine.beginTurn(distributeOrderedDeck(mode, deck, firstSeat, rules), firstSeat, rules)

/**
 * Distribuição pura a partir de um baralho já ordenado ([deck] índice 0 = primeira carta distribuída). **Nada é trocado**
 * (§3.5): os 3 vermelhos ficam na mão, o registro de 3 vermelhos começa vazio e [RoundState.turnsBegun] vale 0.
 *
 * 1. §3.1 [RuleSet.cardsPerHand] cartas a cada jogador, uma por vez, começando por [firstSeat]
 *    e seguindo o sentido anti-horário (§4.2).
 * 2. §3.2 os mortos, com as cartas seguintes distribuídas uma por vez para cada morto,
 *    alternadamente, até cada um ter [RuleSet.cardsPerMorto] cartas.
 * 3. §3.3 o restante forma o monte (60 no individual, 38 em duplas), na mesma ordem (índice 0 = topo).
 * 4. §3.4 lixo vazio.
 */
internal fun distributeOrderedDeck(
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
    val stock = deck.subList(handCards + mortoCards, deck.size)

    return RoundState(
        mode = mode,
        hands = hands.map { it.toList() },
        stock = stock.toList(),
        discardPile = emptyList(),
        mortos = mortos.map { it.toList() },
        redThrees = List(mode.sideCount) { emptyList() },
        tables = List(mode.sideCount) { SideTable() },
        firstSeat = firstSeat,
        currentSeat = firstSeat,
        turnsBegun = 0,
    )
}
