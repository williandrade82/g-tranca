package com.gtranca.game

/**
 * Perfil do jogador (§14.1): nome (até [MAX_NAME] caracteres; vazio = "Você") e avatar (gênero, profissão e aparência).
 * Gravado como texto nas preferências ([encode]/[decode]).
 */
data class PlayerProfile(
    val name: String = "",
    val gender: Gender = Gender.MALE,
    val profession: Profession = Profession.TEACHER,
    val look: Look = Look(skin = 2, hair = HairStyle.SHORT, hairColor = 1),
) {
    /** O perfil como persona (sem sobrenome), para o avatar. */
    fun persona(defaultName: String): Persona = Persona(name.trim().ifEmpty { defaultName }, "", gender, profession, look)

    /** Troca o gênero levando junto um penteado compatível (e sem barba, que é só do masculino). */
    fun withGender(newGender: Gender): PlayerProfile = when {
        newGender == gender -> this
        else -> copy(
            gender = newGender,
            look = look.copy(hair = HairStyle.entries.first { it.gender == newGender }, beard = false),
        )
    }

    fun encode(): String =
        listOf(sanitizeName(name), gender.name, profession.name, look.skin, look.hair.name, look.hairColor, look.beard)
            .joinToString(SEPARATOR)

    companion object {
        const val MAX_NAME = 16

        /** Nome do jogador quando o perfil não tem nome. */
        const val DEFAULT_NAME = "Você"
        private const val SEPARATOR = "\t"

        /** Sem caracteres de controle (o tab separa os campos), no máximo [MAX_NAME] caracteres. */
        fun sanitizeName(raw: String): String = raw.filter { !it.isISOControl() }.take(MAX_NAME)

        /** Lê o texto gravado; ausente ou ilegível (formato antigo, campo inválido) volta ao perfil padrão. */
        fun decode(text: String?): PlayerProfile {
            if (text == null) return PlayerProfile()
            val parts = text.split(SEPARATOR)
            if (parts.size != 7) return PlayerProfile()
            return try {
                val gender = Gender.valueOf(parts[1])
                PlayerProfile(
                    name = sanitizeName(parts[0]),
                    gender = gender,
                    profession = Profession.valueOf(parts[2]),
                    look = Look(
                        skin = parts[3].toInt(),
                        hair = HairStyle.valueOf(parts[4]).also { require(it.gender == gender) },
                        hairColor = parts[5].toInt(),
                        beard = parts[6].toBooleanStrict(),
                    ),
                )
            } catch (_: IllegalArgumentException) {
                PlayerProfile()
            }
        }
    }
}
