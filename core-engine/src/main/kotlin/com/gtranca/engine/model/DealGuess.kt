package com.gtranca.engine.model

import com.gtranca.engine.RuleSet
import kotlinx.serialization.Serializable

/**
 * §3.6 estado público do minijogo "distribuição certeira", antes da 1ª vez da partida. `null` quando
 * `RuleSet.dealGuessBonusEnabled` está desligado (o padrão também mantém legível o JSON salvo antes da regra existir).
 *
 * **Quem palpita** (§3.6): [dealerSeat] é o anterior (§4.2) a [RoundState.firstSeat]; [mortoDealerSeat] é o anterior
 * a [dealerSeat]. Em duplas (4 assentos) são três assentos distintos. Em individual (2 assentos), a dupla aplicação
 * do "anterior" volta ao próprio assento que abre a partida: por decisão explícita do pedido que introduziu esta
 * regra, ele acumula os dois papéis e faz os dois palpites, um atrás do outro (`dealerSeat == mortoDealerSeat ==
 * firstSeat`); ver [dealGuessSeats].
 *
 * @property hand palpite da mão (§3.1), ou `null` enquanto não resolvido.
 * @property morto palpite do morto (§3.2), ou `null` enquanto não resolvido (só existe depois do palpite da mão).
 */
@Serializable
data class DealGuess(
    val dealerSeat: Seat,
    val mortoDealerSeat: Seat,
    val hand: Guess? = null,
    val morto: Guess? = null,
)

/**
 * §3.6 um palpite resolvido: faixa `[start, end]` já normalizada (`start <= end`) numa barra de estágios, e se a
 * largura (`end - start`) acertou o alvo daquele papel (mão ou morto).
 */
@Serializable
data class Guess(val start: Int, val end: Int, val correct: Boolean) {
    init {
        require(start <= end) { "Palpite normalizado deve ter start <= end: $start..$end" }
    }

    /** §3.6 largura da faixa escolhida (fim − início). */
    val width: Int get() = end - start
}

/**
 * §3.6 assentos que palpitam (jogador 2 e jogador 3) a partir de [firstSeat]: o anterior a ele (§4.2) e o anterior
 * a esse. Em individual (2 assentos) os dois papéis caem no próprio [firstSeat] (decisão do pedido que introduziu
 * esta regra): ele faz os dois palpites, um atrás do outro, em vez de delegar o da mão ao adversário.
 */
fun dealGuessSeats(mode: GameMode, firstSeat: Seat): Pair<Seat, Seat> {
    mode.requireSeat(firstSeat)
    if (mode == GameMode.INDIVIDUAL) return firstSeat to firstSeat
    val dealerSeat = mode.previousSeat(firstSeat)
    return dealerSeat to mode.previousSeat(dealerSeat)
}

/** §3.6 estágios da barra do palpite da mão: um por carta do baralho embaralhado do perfil (104, ou 96 com §1). */
fun dealGuessHandStages(rules: RuleSet): Int = Deck.size(rules)

/**
 * §3.6 estágios da barra do palpite do morto: o total do baralho menos a largura escolhida no palpite da mão
 * ([handGuessWidth]), limitado entre **22** (mínimo) e **60** em duplas ou **82** em individual (máximo) — valores
 * fixos do documento, não derivados do tamanho do baralho do perfil.
 */
fun dealGuessMortoStages(mode: GameMode, rules: RuleSet, handGuessWidth: Int): Int {
    val max = if (mode == GameMode.DUPLAS) 60 else 82
    return (Deck.size(rules) - handGuessWidth).coerceIn(22, max)
}
