package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.PlayStyle
import com.gtranca.engine.model.GameMode
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** §14.5 personagens dos jogadores virtuais: sorteio por semente, perfis e nomes sem repetição, nível pela dificuldade. */
class PersonaGeneratorTest {

    @Test
    fun `§14_5 mesma semente gera os mesmos personagens`() {
        repeat(20) { seed ->
            PersonaGenerator.opponents(seed.toLong(), 3, Difficulty.MEDIO, "Beto") shouldBe
                PersonaGenerator.opponents(seed.toLong(), 3, Difficulty.MEDIO, "Beto")
        }
    }

    @Test
    fun `§14_5 sementes diferentes variam os personagens`() {
        val tables = (0L until 40L).map { PersonaGenerator.opponents(it, 3, Difficulty.MEDIO) }.toSet()
        (tables.size > 10) shouldBe true
    }

    @Test
    fun `§14_5 perfis diferentes entre si e nomes sem repeticao nem o nome do jogador`() {
        repeat(300) { seed ->
            val table = PersonaGenerator.opponents(seed.toLong(), 3, Difficulty.MEDIO, "ANA")
            table.map { it.character!!.profile }.shouldBeUnique()
            table.map { it.firstName.lowercase() }.shouldBeUnique()
            table.none { it.firstName.equals("ana", ignoreCase = true) } shouldBe true
        }
    }

    @Test
    fun `§14_5 nome e sobrenome vem do conjunto do perfil`() {
        repeat(100) { seed ->
            PersonaGenerator.opponents(seed.toLong(), 4, Difficulty.FACIL).forEach { persona ->
                persona.character!!.profile.firstNames shouldContain persona.firstName
                PersonaGenerator.surnames shouldContain persona.lastName
                persona.fullName shouldBe "${persona.firstName} ${persona.lastName}"
            }
        }
    }

    @Test
    fun `§14_5 o nivel vem da dificuldade da mesa`() {
        Difficulty.entries.forEach { difficulty ->
            PersonaGenerator.opponents(7, 3, difficulty).forEach {
                it.character!!.level shouldBe BotLevel.of(difficulty)
            }
        }
        BotLevel.of(Difficulty.FACIL) shouldBe BotLevel.INICIANTE
        BotLevel.of(Difficulty.MEDIO) shouldBe BotLevel.MADURO
        BotLevel.of(Difficulty.DIFICIL) shouldBe BotLevel.SENIOR
        BotLevel.entries.map { it.stars } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `§14_5 todos os perfis aparecem ao longo das sementes`() {
        val seen = (0L until 100L).flatMap { PersonaGenerator.opponents(it, 3, Difficulty.MEDIO) }.map { it.character!!.profile }
        seen.toSet() shouldBe BotProfile.entries.toSet()
    }

    @Test
    fun `§14_5 quatro perfis e quantidade invalida`() {
        BotProfile.entries.map { it.code } shouldBe listOf("jovem", "sobrio", "alternativo", "atleta")
        PersonaGenerator.opponents(1, 4, Difficulty.MEDIO).map { it.character!!.profile }.toSet() shouldBe BotProfile.entries.toSet()
        assertThrows<IllegalArgumentException> { PersonaGenerator.opponents(1, BotProfile.entries.size + 1, Difficulty.MEDIO) }
        PersonaGenerator.opponents(1, 0, Difficulty.MEDIO) shouldBe emptyList()
    }

    @Test
    fun `§14_5 cada perfil joga com o seu estilo`() {
        BotProfile.JOVEM.style shouldBe PlayStyle.SAGAZ
        BotProfile.SOBRIO.style shouldBe PlayStyle.SOBRIO
        BotProfile.ALTERNATIVO.style shouldBe PlayStyle.OUSADO
        BotProfile.ATLETA.style shouldBe PlayStyle.AGRESSIVO
        BotProfile.entries.map { it.style }.shouldBeUnique()
    }

    @Test
    fun `§14_5 cada perfil tem nomes proprios suficientes`() {
        BotProfile.entries.forEach { it.firstNames.size shouldBe 10; it.firstNames.shouldBeUnique() }
        BotProfile.entries.flatMap { it.firstNames }.shouldBeUnique()
    }

    @Test
    fun `§14_5 trocar o nivel mantem perfil e nome`() {
        val host = PersonaGenerator.opponents(3, 1, Difficulty.FACIL).single()
        val senior = host.withLevel(BotLevel.SENIOR)
        senior.character shouldBe host.character!!.copy(level = BotLevel.SENIOR)
        senior.fullName shouldBe host.fullName
    }

    @Test
    fun `§14_5 em duplas os adversarios levam a posicao e o parceiro so o nome`() {
        val bot = PersonaGenerator.opponents(5, 1, Difficulty.MEDIO).single()
        val name = bot.fullName
        bot.callName(GameMode.DUPLAS, SeatRole.LEFT_OPPONENT) shouldBe "$name (esq)"
        bot.callName(GameMode.DUPLAS, SeatRole.RIGHT_OPPONENT) shouldBe "$name (dir)"
        bot.callName(GameMode.DUPLAS, SeatRole.PARTNER) shouldBe name
        bot.callName(GameMode.INDIVIDUAL, SeatRole.OPPONENT) shouldBe name
    }

    @Test
    fun `§14_1 nome do jogador e nome curto`() {
        Persona("Bia").fullName shouldBe "Bia"
        Persona("Ana", "Souza").shortName shouldBe "Ana S."
        Persona("Bia").character shouldBe null
        assertThrows<IllegalArgumentException> { Persona("Bia", color = Persona.COLORS) }
        assertThrows<IllegalArgumentException> { Persona("Bia", color = -1) }
    }
}
