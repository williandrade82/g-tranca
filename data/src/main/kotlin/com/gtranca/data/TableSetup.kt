package com.gtranca.data

import com.gtranca.engine.model.GameMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Última configuração da mesa (§14.4, docs/telas.md 3.2), lembrada no aparelho: **modo, nível dos robôs, aposta, regras e
 * pontuação-alvo**. Os lugares (robôs e jogadores) **não** são lembrados: a Seleção de jogadores sempre abre com tudo VAGO.
 * Versões anteriores gravavam também os robôs marcados; esse campo antigo é ignorado ao ler. Os valores ficam como foram
 * gravados; o ajuste ao que vale agora (saldo, tabela de apostas do nível, regras salvas) é da interface, ao carregar.
 *
 * @property difficultyId `Difficulty.id` do `:ai` (o `:data` não depende dele).
 * @property rules escolha das regras: `CLASSICA`, `PAULISTA` ou `CUSTOM`.
 * @property target pontuação-alvo como foi digitada.
 */
@Serializable
data class TableSetup(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficultyId: String = "medio",
    val rules: String = "CLASSICA",
    val stake: Int = 0,
    val target: String = "",
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Texto ilegível: `null` (como se nunca tivesse montado uma mesa). */
        fun decode(text: String): TableSetup? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()

        /**
         * Formato antigo da "Criar mesa" (versão 1, campos separados por tabulação: versão, tipo, nome da mesa, modo, nível,
         * regras, aposta, alvo). O tipo e o nome da mesa deixaram de existir e são ignorados; não havia robôs marcados.
         */
        fun fromLegacy(text: String): TableSetup? {
            val parts = text.split("\t")
            if (parts.size < 8 || parts[0] != "1") return null
            val mode = GameMode.entries.firstOrNull { it.name == parts[3] } ?: return null
            return TableSetup(
                mode = mode,
                difficultyId = parts[4],
                rules = parts[5],
                stake = parts[6].toIntOrNull() ?: 0,
                target = parts[7],
            )
        }
    }
}
