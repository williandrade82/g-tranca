package com.gtranca.data

import com.gtranca.engine.model.GameMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Última montagem da mesa (§14.4, docs/telas.md 3.2), lembrada no aparelho: modo, nível dos robôs, regras, aposta,
 * pontuação-alvo e os **robôs marcados**, na ordem em que foram marcados (§16.2: a ordem dos assentos segue a da escolha).
 * Os jogadores próximos não são lembrados. Os valores ficam como foram gravados; o ajuste ao que vale agora (saldo,
 * regras salvas, modo com muitos marcados) é da interface, ao carregar.
 *
 * @property difficultyId `Difficulty.id` do `:ai` (o `:data` não depende dele).
 * @property rules escolha das regras: `CLASSICA`, `PAULISTA` ou `CUSTOM`.
 * @property target pontuação-alvo como foi digitada.
 * @property robots ids dos personagens marcados (`BotProfile.code`).
 */
@Serializable
data class TableSetup(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficultyId: String = "medio",
    val rules: String = "CLASSICA",
    val stake: Int = 0,
    val target: String = "",
    val robots: List<String> = emptyList(),
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
