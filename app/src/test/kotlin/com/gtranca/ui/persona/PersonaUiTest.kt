package com.gtranca.ui.persona

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.Persona
import com.gtranca.game.PersonaGenerator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** §14.5 comunicação: nome completo com a posição (duplas), sem profissão em lugar nenhum. */
class PersonaUiTest {
    private val you = Persona("Bia")
    private val bots = PersonaGenerator.opponents(9, 3, Difficulty.MEDIO)

    // Assentos: 0 = jogador, 1 = adversário à direita, 2 = parceiro, 3 = adversário à esquerda.
    private val table = listOf(you, bots[0], bots[1], bots[2])

    @Test
    fun `§14_5 em duplas os adversarios levam a posicao e o parceiro so o nome`() {
        callNamesOfSide(GameMode.DUPLAS, Side(0), Seat(0), table) shouldBe listOf("Você", bots[1].fullName)
        callNamesOfSide(GameMode.DUPLAS, Side(1), Seat(0), table) shouldBe listOf("${bots[0].fullName} (dir)", "${bots[2].fullName} (esq)")
    }

    @Test
    fun `§14_5 a posicao e sempre em relacao ao jogador`() {
        // Vista do assento 1: o assento 0 passa a ser o adversário à esquerda; o 2, o da direita.
        callNamesOfSide(GameMode.DUPLAS, Side(0), Seat(1), table) shouldBe
            listOf("${you.fullName} (esq)", "${bots[1].fullName} (dir)")
    }

    @Test
    fun `§14_5 no individual so o nome completo, sem posicao`() {
        val two = listOf(you, bots[0])
        callNamesOfSide(GameMode.INDIVIDUAL, Side(1), Seat(0), two) shouldBe listOf(bots[0].fullName)
        callNamesOfSide(GameMode.INDIVIDUAL, Side(0), Seat(0), two) shouldBe listOf("Você")
    }

    @Test
    fun `§14_5 sem perfis nao ha nomes`() {
        callNamesOfSide(GameMode.DUPLAS, Side(1), Seat(0), emptyList()) shouldBe emptyList()
    }

    @Test
    fun `§14_5 nao sobra texto de profissao nem de genero nos recursos`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        listOf("profession_", "gender_", "Profissão", "role_partner_", "hair_", "face_").forEach {
            (it in strings) shouldBe false
        }
    }
}
