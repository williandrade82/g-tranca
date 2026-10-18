package com.gtranca.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.1 perfil do jogador: nome, avatar (foto ou medalhão com inicial e cor) e gravação como texto. */
class PlayerProfileTest {

    @Test
    fun `§14_1 grava e le o perfil inteiro`() {
        val profile = PlayerProfile(name = "Bia Souza", color = 5)
        PlayerProfile.decode(profile.encode()) shouldBe profile
        val withPhoto = PlayerProfile("Zé", color = 2, hasPhoto = true)
        PlayerProfile.decode(withPhoto.encode()) shouldBe withPhoto
    }

    @Test
    fun `§14_1 foto pessoal so vale com o arquivo informado`() {
        val profile = PlayerProfile("Bia", hasPhoto = true)
        profile.persona("Você", "/fotos/eu.jpg").photo shouldBe "/fotos/eu.jpg"
        profile.persona("Você").photo shouldBe null
        PlayerProfile("Bia").persona("Você", "/fotos/eu.jpg").photo shouldBe null
    }

    @Test
    fun `§14_1 medalhao usa o nome e a cor do perfil e nunca e um personagem`() {
        val persona = PlayerProfile("Bia", color = 3).persona("Você")
        persona.color shouldBe 3
        persona.character shouldBe null
        persona.firstName shouldBe "Bia"
    }

    @Test
    fun `§14_1 perfil gravado no formato antigo (persona por profissao) mantem nome e foto`() {
        PlayerProfile.decode("Rui\tMALE\tPILOT\t2\tSHORT\t1\ttrue") shouldBe PlayerProfile("Rui")
        PlayerProfile.decode("Rui\tMALE\tPILOT\t2\tSHORT\t1\ttrue\tOVAL") shouldBe PlayerProfile("Rui")
        PlayerProfile.decode("Rui\tMALE\tPILOT\t2\tSHORT\t1\ttrue\tOVAL\ttrue") shouldBe PlayerProfile("Rui", hasPhoto = true)
        PlayerProfile.decode("Rui\tMALE\tPILOT\t2\tSHORT\t1\ttrue\tOVAL\ttalvez") shouldBe PlayerProfile()
    }

    @Test
    fun `§14_1 texto ausente ou ilegivel volta ao perfil padrao`() {
        val default = PlayerProfile()
        PlayerProfile.decode(null) shouldBe default
        PlayerProfile.decode("") shouldBe default
        PlayerProfile.decode("lixo") shouldBe default
        PlayerProfile.decode("a\t${PlayerProfile.COLORS}\tfalse") shouldBe default // cor fora da paleta
        PlayerProfile.decode("a\t-1\tfalse") shouldBe default
        PlayerProfile.decode("a\tx\tfalse") shouldBe default
        PlayerProfile.decode("a\t1\tTALVEZ") shouldBe default
    }

    @Test
    fun `§14_1 nome tem no maximo 16 caracteres e sem controles`() {
        PlayerProfile.sanitizeName("a".repeat(40)).length shouldBe PlayerProfile.MAX_NAME
        PlayerProfile.sanitizeName("Ana\tMaria\n") shouldBe "AnaMaria"
        // Um nome com tab não quebra a gravação.
        PlayerProfile.decode(PlayerProfile(name = "A\tB").encode()).name shouldBe "AB"
    }

    @Test
    fun `§14_1 nome vazio vira o padrao no avatar e o sobrenome fica vazio`() {
        PlayerProfile(name = "   ").persona("Você").fullName shouldBe "Você"
        PlayerProfile(name = " Rui ").persona("Você").let {
            it.fullName shouldBe "Rui"
            it.lastName shouldBe ""
        }
    }

    @Test
    fun `§14_1 inicial do medalhao e a primeira letra em maiuscula`() {
        Persona("bia").initial shouldBe "B"
        Persona("Édson").initial shouldBe "É"
        Persona("").initial shouldBe "?"
    }
}
