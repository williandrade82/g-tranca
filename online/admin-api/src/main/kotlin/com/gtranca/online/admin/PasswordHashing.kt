package com.gtranca.online.admin

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies

/**
 * Hash de senha da equipe do console (Sec18.7, espelha a tecnica de Sec18.1): bcrypt com pre-hash SHA-512 (evita o
 * teto de 72 bytes do bcrypt puro, sem impor "outra exigencia" alem do minimo de caracteres). Implementado aqui,
 * sem depender do lobby-service: cada servico cuida da propria senha, cada um com o seu proprio esquema de contas
 * (equipe do console != conta de jogador).
 */
object PasswordHashing {
    private val version = BCrypt.Version.VERSION_2A
    private val longPassword = LongPasswordStrategies.hashSha512(version)

    fun hash(password: String, cost: Int): String =
        BCrypt.with(version, longPassword).hashToString(cost, password.toCharArray())

    fun matches(password: String, hash: String): Boolean =
        runCatching { BCrypt.verifyer(version, longPassword).verify(password.toCharArray(), hash).verified }.getOrDefault(false)
}
