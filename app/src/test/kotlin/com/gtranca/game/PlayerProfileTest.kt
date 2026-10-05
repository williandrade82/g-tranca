package com.gtranca.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.1 perfil do jogador: nome, avatar e gravação como texto. */
class PlayerProfileTest {

    @Test
    fun `§14_1 grava e le o perfil inteiro`() {
        val profile = PlayerProfile(
            name = "Bia Souza",
            gender = Gender.FEMALE,
            profession = Profession.SAILOR,
            look = Look(skin = 4, hair = HairStyle.PONYTAIL, hairColor = 5, face = FaceShape.HEART),
        )
        PlayerProfile.decode(profile.encode()) shouldBe profile
        val bearded = PlayerProfile("Zé", Gender.MALE, Profession.MECHANIC, Look(0, HairStyle.BALD, 0, beard = true))
        PlayerProfile.decode(bearded.encode()) shouldBe bearded
    }

    @Test
    fun `§14_1 perfil gravado no formato anterior, sem o rosto, ainda e lido`() {
        val old = "Rui\tMALE\tPILOT\t2\tSHORT\t1\ttrue"
        PlayerProfile.decode(old) shouldBe PlayerProfile("Rui", Gender.MALE, Profession.PILOT, Look(2, HairStyle.SHORT, 1, beard = true))
        PlayerProfile.decode("$old\tQUADRADINHO") shouldBe PlayerProfile()
    }

    @Test
    fun `§14_1 texto ausente ou ilegivel volta ao perfil padrao`() {
        val default = PlayerProfile()
        PlayerProfile.decode(null) shouldBe default
        PlayerProfile.decode("") shouldBe default
        PlayerProfile.decode("lixo") shouldBe default
        PlayerProfile.decode("a\tMALE\tTEACHER\t9\tSHORT\t0\tfalse") shouldBe default // tom de pele fora da paleta
        PlayerProfile.decode("a\tMALE\tTEACHER\t1\tLONG\t0\tfalse") shouldBe default // penteado de outro gênero
        PlayerProfile.decode("a\tMALE\tASTRONAUTA\t1\tSHORT\t0\tfalse") shouldBe default
        PlayerProfile.decode("a\tMALE\tTEACHER\t1\tSHORT\t0\tTALVEZ") shouldBe default
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
    fun `§14_1 trocar o genero leva um penteado compativel e tira a barba`() {
        val male = PlayerProfile(look = Look(1, HairStyle.CURLY_SHORT, 2, beard = true))
        val female = male.withGender(Gender.FEMALE)
        female.gender shouldBe Gender.FEMALE
        female.look.hair.gender shouldBe Gender.FEMALE
        female.look.beard shouldBe false
        female.look.skin shouldBe 1
        female.look.hairColor shouldBe 2
        male.withGender(Gender.MALE) shouldBe male
    }
}
