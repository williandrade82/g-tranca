package com.gtranca.online.contracts

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TableIdTest {
    @Test
    fun `18_2 id tem 6 caracteres sem 0 O 1 I L`() = runBlocking {
        checkAll(Arb.long()) { seed ->
            val id = TableId.generate(Random(seed))
            id.length shouldBe 6
            id.none { it in "01OIL" } shouldBe true
            TableId.isValid(id) shouldBe true
        }
    }

    @Test
    fun `18_2 sem diferenca entre maiusculas e minusculas`() {
        TableId.normalize("abc234") shouldBe "ABC234"
        TableId.normalize("  Abc234 ") shouldBe "ABC234"
    }

    @Test
    fun `18_2 recusa caracteres que se confundem e tamanho errado`() {
        TableId.isValid("ABC0EF") shouldBe false
        TableId.isValid("ABCOEF") shouldBe false
        TableId.isValid("ABC1EF") shouldBe false
        TableId.isValid("ABCIEF") shouldBe false
        TableId.isValid("ABCLEF") shouldBe false
        TableId.isValid("ABC23") shouldBe false
        TableId.isValid("ABC2345") shouldBe false
    }

    @Test
    fun `18_2 mesmo seed gera mesmo id`() {
        TableId.generate(Random(7)) shouldBe TableId.generate(Random(7))
    }
}
