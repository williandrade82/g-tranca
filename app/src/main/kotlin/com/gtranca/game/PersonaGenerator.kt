package com.gtranca.game

import kotlin.random.Random

/**
 * Sorteio dos perfis dos jogadores virtuais no início de cada jogo (§14.1). Determinístico: a mesma semente do jogo
 * gera as mesmas pessoas (por isso "Continuar" mostra os mesmos adversários, sem gravar nada a mais no jogo salvo).
 */
object PersonaGenerator {

    private val FEMALE_NAMES = listOf(
        "Ana", "Beatriz", "Camila", "Daniela", "Eduarda", "Fernanda", "Gabriela", "Helena", "Isabela", "Juliana",
        "Larissa", "Mariana", "Natália", "Olívia", "Patrícia", "Rafaela", "Sofia", "Tatiane", "Valentina", "Vanessa",
        "Aline", "Bianca", "Carolina", "Letícia", "Luiza", "Marina", "Paula", "Renata", "Sandra", "Thaís",
    )
    private val MALE_NAMES = listOf(
        "André", "Bruno", "Carlos", "Daniel", "Eduardo", "Felipe", "Gabriel", "Henrique", "Igor", "João",
        "Lucas", "Marcelo", "Nicolas", "Otávio", "Paulo", "Rafael", "Samuel", "Thiago", "Vitor", "Wagner",
        "Alexandre", "Caio", "Diego", "Fábio", "Gustavo", "Leonardo", "Mateus", "Pedro", "Ricardo", "Rodrigo",
    )
    private val SURNAMES = listOf(
        "Silva", "Santos", "Oliveira", "Souza", "Pereira", "Costa", "Rodrigues", "Almeida", "Nascimento", "Lima",
        "Araújo", "Fernandes", "Carvalho", "Gomes", "Martins", "Rocha", "Ribeiro", "Alves", "Monteiro", "Mendes",
        "Barros", "Freitas", "Barbosa", "Pinto", "Moreira", "Cavalcanti", "Dias", "Castro", "Campos", "Cardoso",
        "Teixeira", "Correia", "Azevedo", "Cunha", "Moura", "Nunes", "Ramos", "Vieira", "Duarte", "Farias",
    )

    /** Nomes que o sorteio usa, por gênero. */
    fun firstNames(gender: Gender): List<String> = if (gender == Gender.MALE) MALE_NAMES else FEMALE_NAMES

    /** Sobrenomes que o sorteio usa. */
    val surnames: List<String> get() = SURNAMES

    /**
     * [count] perfis para a semente do jogo: nomes e profissões todos diferentes entre si e nenhum com o primeiro
     * nome de [avoidName] (o do jogador), sem diferenciar maiúsculas.
     */
    fun opponents(gameSeed: Long, count: Int, avoidName: String = ""): List<Persona> {
        require(count in 0..Profession.entries.size) { "Quantidade inválida: $count" }
        val random = Random(gameSeed xor SEED_SALT)
        val professions = Profession.entries.shuffled(random).take(count)
        val taken = mutableSetOf(avoidName.trim().lowercase())
        return professions.map { profession ->
            val gender = if (random.nextBoolean()) Gender.MALE else Gender.FEMALE
            val firstName = firstNames(gender).filter { it.lowercase() !in taken }.random(random)
            taken += firstName.lowercase()
            create(random, gender, profession, firstName)
        }
    }

    /** Um perfil com [gender] e [profession] dados; a aparência é sorteada (o nome, se não vier, também). */
    fun create(random: Random, gender: Gender, profession: Profession, firstName: String? = null): Persona {
        val hair = HairStyle.entries.filter { it.gender == gender }.random(random)
        val beard = gender == Gender.MALE && random.nextInt(3) == 0
        return Persona(
            firstName = firstName ?: firstNames(gender).random(random),
            lastName = SURNAMES.random(random),
            gender = gender,
            profession = profession,
            look = Look(
                skin = random.nextInt(Look.SKIN_TONES),
                hair = hair,
                hairColor = random.nextInt(Look.HAIR_COLORS),
                beard = beard,
                face = FaceShape.entries.random(random),
            ),
        )
    }

    private const val SEED_SALT = 0x5EEDFACEL
}
