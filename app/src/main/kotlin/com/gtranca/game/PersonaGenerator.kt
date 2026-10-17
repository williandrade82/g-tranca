package com.gtranca.game

import com.gtranca.ai.Difficulty
import kotlin.random.Random

/**
 * Sorteio dos personagens dos jogadores virtuais no início de cada jogo (§14.5). Determinístico: a mesma semente do
 * jogo gera os mesmos personagens (por isso "Continuar" mostra os mesmos adversários, sem gravar nada a mais no jogo salvo).
 */
object PersonaGenerator {

    private val SURNAMES = listOf(
        "Silva", "Santos", "Oliveira", "Souza", "Pereira", "Costa", "Rodrigues", "Almeida", "Nascimento", "Lima",
        "Araújo", "Fernandes", "Carvalho", "Gomes", "Martins", "Rocha", "Ribeiro", "Alves", "Monteiro", "Mendes",
        "Barros", "Freitas", "Barbosa", "Pinto", "Moreira", "Cavalcanti", "Dias", "Castro", "Campos", "Cardoso",
        "Teixeira", "Correia", "Azevedo", "Cunha", "Moura", "Nunes", "Ramos", "Vieira", "Duarte", "Farias",
    )

    /** Sobrenomes que o sorteio usa. */
    val surnames: List<String> get() = SURNAMES

    /**
     * [count] personagens para a semente do jogo, no nível da [difficulty]: perfis todos diferentes entre si, nomes
     * todos diferentes e nenhum com o primeiro nome de [avoidName] (o do jogador), sem diferenciar maiúsculas.
     */
    fun opponents(gameSeed: Long, count: Int, difficulty: Difficulty, avoidName: String = ""): List<Persona> {
        require(count in 0..BotProfile.entries.size) { "Quantidade inválida: $count" }
        val random = Random(gameSeed xor SEED_SALT)
        val level = BotLevel.of(difficulty)
        val taken = mutableSetOf(avoidName.trim().lowercase())
        return BotProfile.entries.shuffled(random).take(count).map { profile ->
            val firstName = profile.firstNames.filter { it.lowercase() !in taken }.random(random)
            taken += firstName.lowercase()
            Persona(firstName, SURNAMES.random(random), BotCharacter(profile, level))
        }
    }

    private const val SEED_SALT = 0x5EEDFACEL
}
