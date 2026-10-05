package com.gtranca.game

/** Gênero do perfil (§14.1): define nomes, cabelos e a forma da profissão. */
enum class Gender { MALE, FEMALE }

/** Profissão de um avatar (§14.1): uniforme e acessório próprios. */
enum class Profession { DOCTOR, CHEF, ENGINEER, TEACHER, FIREFIGHTER, SCIENTIST, PILOT, ARTIST }

/** Penteados; cada um só serve a um gênero ([gender]). */
enum class HairStyle(val gender: Gender) {
    SHORT(Gender.MALE),
    SIDE_SWEEP(Gender.MALE),
    CURLY_SHORT(Gender.MALE),
    BALD(Gender.MALE),
    LONG(Gender.FEMALE),
    BOB(Gender.FEMALE),
    BUN(Gender.FEMALE),
    PONYTAIL(Gender.FEMALE),
    CURLY_LONG(Gender.FEMALE),
}

/** Aparência do avatar: índices nas paletas da tela ([SKIN_TONES] tons de pele, [HAIR_COLORS] cores de cabelo). */
data class Look(val skin: Int, val hair: HairStyle, val hairColor: Int, val beard: Boolean = false) {
    init {
        require(skin in 0 until SKIN_TONES) { "Tom de pele inválido: $skin" }
        require(hairColor in 0 until HAIR_COLORS) { "Cor de cabelo inválida: $hairColor" }
        require(!beard || hair.gender == Gender.MALE) { "Barba só no gênero masculino" }
    }

    companion object {
        const val SKIN_TONES = 5
        const val HAIR_COLORS = 6
    }
}

/** Perfil de um jogador (§14.1). [lastName] vazio = só o nome (perfil digitado pelo jogador). */
data class Persona(
    val firstName: String,
    val lastName: String,
    val gender: Gender,
    val profession: Profession,
    val look: Look,
) {
    init {
        require(look.hair.gender == gender) { "Penteado incompatível com o gênero" }
    }

    /** "Ana Souza". */
    val fullName: String get() = if (lastName.isEmpty()) firstName else "$firstName $lastName"

    /** "Ana S." (cabe na pílula da mesa). */
    val shortName: String get() = if (lastName.isEmpty()) firstName else "$firstName ${lastName.first()}."
}
