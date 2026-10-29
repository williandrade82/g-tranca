package com.gtranca.online.contracts

import kotlin.random.Random

/**
 * Id da mesa (§18.2): 6 caracteres, sem 0/O e 1/I/L, sem diferenca entre maiusculas e minusculas.
 * A unicidade entre mesas abertas e garantida por quem guarda as mesas (lobby-service), nao aqui.
 */
object TableId {
    const val LENGTH = 6
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Forma canonica (maiusculas, sem espacos nas pontas) ou null se invalido. */
    fun normalize(input: String): String? {
        val id = input.trim().uppercase()
        return id.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun isValid(input: String): Boolean = normalize(input) != null
}
