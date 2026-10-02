package com.gtranca.engine.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Naipe (§1). Copas e ouros são vermelhos; espadas e paus, pretos. */
enum class Suit(val symbol: Char, val isRed: Boolean) {
    HEARTS('H', true),
    DIAMONDS('D', true),
    SPADES('S', false),
    CLUBS('C', false);

    companion object {
        fun fromSymbol(symbol: Char): Suit =
            entries.firstOrNull { it.symbol == symbol }
                ?: throw IllegalArgumentException("Naipe inválido: '$symbol'")
    }
}

/** Valor da carta (§1), do 2 ao Ás. O Ás é sempre carta alta (§6.2). */
enum class Rank(val symbol: Char) {
    TWO('2'), THREE('3'), FOUR('4'), FIVE('5'), SIX('6'), SEVEN('7'), EIGHT('8'),
    NINE('9'), TEN('T'), JACK('J'), QUEEN('Q'), KING('K'), ACE('A');

    /** §6.3 o 2 é sempre coringa. */
    val isWild: Boolean get() = this == TWO

    val isThree: Boolean get() = this == THREE

    companion object {
        fun fromSymbol(symbol: Char): Rank =
            entries.firstOrNull { it.symbol == symbol }
                ?: throw IllegalArgumentException("Valor inválido: '$symbol'")
    }
}

/**
 * Carta física. Há duas cópias de cada valor/naipe (§1); [deck] (0 ou 1) as distingue,
 * de modo que cada carta é única e a igualdade por valor funciona.
 *
 * Notação curta (testes, logs e JSON): valor + naipe, com `'` para o 2º baralho.
 * Ex.: `"7H"`, `"TH"` (10), `"QS"`, `"AD"`, `"2C"`, `"7H'"`.
 */
@Serializable(with = CardSerializer::class)
data class Card(val rank: Rank, val suit: Suit, val deck: Int = 0) {
    init {
        require(deck == 0 || deck == 1) { "deck deve ser 0 ou 1: $deck" }
    }

    /** §6.3 coringa. */
    val isWild: Boolean get() = rank.isWild

    /** §6.5 3 de copas ou ouros. */
    val isRedThree: Boolean get() = rank.isThree && suit.isRed

    /** §6.6 3 de espadas ou paus. */
    val isBlackThree: Boolean get() = rank.isThree && !suit.isRed

    override fun toString(): String = "${rank.symbol}${suit.symbol}" + if (deck == 1) "'" else ""

    companion object {
        /** Lê a notação curta (ver [Card]). Lança [IllegalArgumentException] se inválida. */
        fun parse(text: String): Card {
            require(text.length == 2 || (text.length == 3 && text[2] == '\'')) { "Carta inválida: \"$text\"" }
            return Card(Rank.fromSymbol(text[0]), Suit.fromSymbol(text[1]), if (text.length == 3) 1 else 0)
        }
    }
}

/** Serializa a carta como a notação curta (ex.: `"7H'"`). */
object CardSerializer : KSerializer<Card> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.gtranca.engine.model.Card", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Card) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Card = Card.parse(decoder.decodeString())
}
