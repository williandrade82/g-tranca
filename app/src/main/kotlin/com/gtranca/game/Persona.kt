package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode

/** §14.5 os 4 perfis de robô dos jogadores virtuais; [firstNames] é o conjunto de nomes sorteados para o perfil. */
enum class BotProfile(val code: String, val firstNames: List<String>) {
    JOVEM("jovem", listOf("Davi", "Enzo", "Théo", "Lucas", "Gabriel", "Miguel", "Caio", "Vitor", "Heitor", "Nicolas")),
    SOBRIO("sobrio", listOf("Henrique", "Eduardo", "Augusto", "Fernando", "Roberto", "Ricardo", "Antônio", "Marcos", "Alberto", "Sérgio")),
    ALTERNATIVO("alternativo", listOf("Ariel", "Caetano", "Dandara", "Jaci", "Íris", "Raul", "Naiara", "Gil", "Lia", "Ravi")),
    ATLETA("atleta", listOf("Marcelo", "Rodrigo", "Thiago", "Leandro", "Maurício", "Alexandre", "Diego", "Fábio", "Rogério", "Wagner")),
}

/** §14.5 nível do personagem, ligado à dificuldade escolhida; [stars] é a insígnia (1 a 3 estrelas). */
enum class BotLevel(val code: String, val stars: Int, val difficulty: Difficulty) {
    INICIANTE("iniciante", 1, Difficulty.FACIL),
    MADURO("maduro", 2, Difficulty.MEDIO),
    SENIOR("senior", 3, Difficulty.DIFICIL),
    ;

    companion object {
        fun of(difficulty: Difficulty): BotLevel = entries.first { it.difficulty == difficulty }
    }
}

/** §14.5 personagem de um jogador virtual: perfil de robô e nível (12 combinações, cada uma com as suas imagens). */
data class BotCharacter(val profile: BotProfile, val level: BotLevel)

/**
 * Identidade exibida de um jogador (§14.1, §14.5): nome e avatar. Jogador virtual: [character] (imagem do personagem).
 * Jogador humano ([character] nulo): [photo] pessoal ou medalhão com a inicial e a [color] da paleta.
 * [lastName] vazio = só o nome (perfil digitado pelo jogador).
 */
data class Persona(
    val firstName: String,
    val lastName: String = "",
    val character: BotCharacter? = null,
    /** Humano sem foto: índice da cor do medalhão (0 until [COLORS]). */
    val color: Int = 0,
    /** §14.1 foto pessoal do jogador (caminho do arquivo no aparelho); `null` = vale o medalhão. */
    val photo: String? = null,
) {
    init {
        require(color in 0 until COLORS) { "Cor do medalhão inválida: $color" }
    }

    /** "Davi Silva". */
    val fullName: String get() = if (lastName.isEmpty()) firstName else "$firstName $lastName"

    /** "Davi S." (cabe na pílula da mesa). */
    val shortName: String get() = if (lastName.isEmpty()) firstName else "$firstName ${lastName.first()}."

    /** Letra do medalhão do jogador. */
    val initial: String get() = firstName.firstOrNull()?.uppercase() ?: "?"

    /** Mesmo personagem em outro nível (mesas dos jogadores virtuais, §14.4). */
    fun withLevel(level: BotLevel): Persona = copy(character = character?.copy(level = level))

    /**
     * §14.5 nome na comunicação do jogo: em duplas, os adversários levam a posição em relação ao jogador
     * ("Nome Sobrenome (dir)" / "(esq)"); o parceiro e o individual, só o nome.
     */
    fun callName(mode: GameMode, role: SeatRole): String = when {
        mode != GameMode.DUPLAS -> fullName
        role == SeatRole.LEFT_OPPONENT -> "$fullName (esq)"
        role == SeatRole.RIGHT_OPPONENT -> "$fullName (dir)"
        else -> fullName
    }

    companion object {
        /** Cores disponíveis para o medalhão do jogador. */
        const val COLORS = 8
    }
}
