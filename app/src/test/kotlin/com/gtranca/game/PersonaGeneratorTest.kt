package com.gtranca.game

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** §14.1 perfis dos jogadores virtuais: sorteio por semente, sem repetição e compatível com o gênero. */
class PersonaGeneratorTest {

    @Test
    fun `§14_1 a mesma semente gera as mesmas pessoas`() {
        repeat(50) { seed ->
            PersonaGenerator.opponents(seed.toLong(), 3, "Beto") shouldBe PersonaGenerator.opponents(seed.toLong(), 3, "Beto")
        }
    }

    @Test
    fun `§14_1 sementes diferentes geram mesas diferentes`() {
        val tables = (0L until 40L).map { PersonaGenerator.opponents(it, 3) }.toSet()
        (tables.size > 30) shouldBe true
    }

    @Test
    fun `§14_1 nomes e profissoes nao se repetem na mesa e ninguem usa o nome do jogador`() {
        repeat(500) { seed ->
            val table = PersonaGenerator.opponents(seed.toLong(), 3, "ANA")
            table.map { it.firstName.lowercase() }.shouldBeUnique()
            table.map { it.profession }.shouldBeUnique()
            table.none { it.firstName.equals("ana", ignoreCase = true) } shouldBe true
        }
    }

    @Test
    fun `§14_1 nome, sobrenome e avatar sao compativeis com o genero`() {
        repeat(500) { seed ->
            PersonaGenerator.opponents(seed.toLong(), 3).forEach { persona ->
                PersonaGenerator.firstNames(persona.gender) shouldContain persona.firstName
                PersonaGenerator.surnames shouldContain persona.lastName
                persona.look.hair.gender shouldBe persona.gender
                if (persona.look.beard) persona.gender shouldBe Gender.MALE
            }
        }
    }

    @Test
    fun `§14_1 os dois generos e varios penteados aparecem`() {
        val all = (0L until 200L).flatMap { PersonaGenerator.opponents(it, 3) }
        all.map { it.gender }.toSet() shouldBe Gender.entries.toSet()
        (all.map { it.look.hair }.toSet().size >= 7) shouldBe true
        all.map { it.look.skin }.toSet().size shouldBe Look.SKIN_TONES
    }

    @Test
    fun `nomes curto e completo`() {
        val persona = Persona("Ana", "Souza", Gender.FEMALE, Profession.DOCTOR, Look(1, HairStyle.BOB, 2))
        persona.fullName shouldBe "Ana Souza"
        persona.shortName shouldBe "Ana S."
        Persona("Bia", "", Gender.FEMALE, Profession.CHEF, Look(0, HairStyle.LONG, 0)).let {
            it.fullName shouldBe "Bia"
            it.shortName shouldBe "Bia"
        }
    }

    @Test
    fun `perfil incompativel e recusado`() {
        assertThrows<IllegalArgumentException> { Persona("Ana", "S", Gender.FEMALE, Profession.CHEF, Look(0, HairStyle.SHORT, 0)) }
        assertThrows<IllegalArgumentException> { Look(0, HairStyle.LONG, 0, beard = true) }
        assertThrows<IllegalArgumentException> { Look(Look.SKIN_TONES, HairStyle.SHORT, 0) }
        assertThrows<IllegalArgumentException> { PersonaGenerator.opponents(1, Profession.entries.size + 1) }
        PersonaGenerator.opponents(1, 0) shouldBe emptyList()
    }
}
