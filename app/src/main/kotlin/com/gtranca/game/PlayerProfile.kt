package com.gtranca.game

/**
 * Perfil do jogador (§14.1): nome (até [MAX_NAME] caracteres; vazio = "Você") e avatar: foto pessoal ([hasPhoto]; o
 * arquivo fica no aparelho) ou, sem foto, medalhão com a inicial na [color] escolhida (índice da paleta).
 * Gravado como texto nas preferências ([encode]/[decode]).
 */
data class PlayerProfile(
    val name: String = "",
    val color: Int = 0,
    /** §14.1 o avatar é a foto pessoal guardada no aparelho. */
    val hasPhoto: Boolean = false,
) {
    /** O perfil como persona (sem sobrenome), para o avatar; com [hasPhoto], a foto em [photoPath]. */
    fun persona(defaultName: String, photoPath: String? = null): Persona =
        Persona(name.trim().ifEmpty { defaultName }, "", color = color, photo = photoPath?.takeIf { hasPhoto })

    fun encode(): String = listOf(sanitizeName(name), color, hasPhoto).joinToString(SEPARATOR)

    companion object {
        const val MAX_NAME = 16

        /** Cores do medalhão (§14.1). */
        const val COLORS = Persona.COLORS

        /** Nome do jogador quando o perfil não tem nome. */
        const val DEFAULT_NAME = "Você"
        private const val SEPARATOR = "\t"

        /** Sem caracteres de controle (o tab separa os campos), no máximo [MAX_NAME] caracteres. */
        fun sanitizeName(raw: String): String = raw.filter { !it.isISOControl() }.take(MAX_NAME)

        /**
         * Lê o texto gravado; ausente ou ilegível volta ao perfil padrão. Aceita o formato antigo (7 a 9 campos, com
         * persona por gênero e profissão): dele só valem o nome e a foto.
         */
        fun decode(text: String?): PlayerProfile {
            if (text == null) return PlayerProfile()
            val parts = text.split(SEPARATOR)
            return try {
                when (parts.size) {
                    3 -> PlayerProfile(
                        sanitizeName(parts[0]),
                        parts[1].toInt().also { require(it in 0 until COLORS) },
                        parts[2].toBooleanStrict(),
                    )
                    in 7..9 -> PlayerProfile(sanitizeName(parts[0]), 0, parts.getOrNull(8)?.toBooleanStrict() ?: false)
                    else -> PlayerProfile()
                }
            } catch (_: IllegalArgumentException) {
                PlayerProfile()
            }
        }
    }
}
