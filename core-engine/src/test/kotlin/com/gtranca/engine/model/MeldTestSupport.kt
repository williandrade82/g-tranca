package com.gtranca.engine.model

import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe

/** "5H 6H 2C" → lista de cartas na notação curta. */
fun cards(text: String): List<Card> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(Card::parse)

fun <T> RuleResult<T>.shouldBeOk(): T = when (this) {
    is RuleResult.Ok -> value
    is RuleResult.Failure -> fail("Esperado sucesso, veio erro $error")
}

infix fun <T> RuleResult<T>.shouldFailWith(expected: RuleError) {
    when (this) {
        is RuleResult.Ok -> fail("Esperado erro $expected, veio sucesso: $value")
        is RuleResult.Failure -> error shouldBe expected
    }
}

/** Cria o conjunto exigindo sucesso. */
fun meld(text: String): Meld = Meld.create(cards(text)).shouldBeOk()
