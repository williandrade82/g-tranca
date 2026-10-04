package com.gtranca.data

import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import kotlinx.serialization.Serializable

/**
 * Evento público de uma ação aplicada (DTO do `:data`, sem depender do `:ai`): quem agiu, a ação e as cartas do lixo
 * que foram para a mão ao pegá-lo (§5.2, públicas por §5.6). É o que reconstrói a memória dos jogadores virtuais ao
 * restaurar.
 */
@Serializable
data class SavedEvent(val seat: Int, val action: Action, val takenFromDiscard: List<Card> = emptyList())

/** Configuração escolhida no início (§14), sem depender do `:ai`: a dificuldade vai pelo identificador. */
@Serializable
data class SavedConfig(val mode: GameMode, val difficultyId: String, val targetScore: Int)

/**
 * Jogo salvo (um só por vez). Formato JSON versionado ([formatVersion]).
 *
 * @property gameId identificador do jogo (para as estatísticas contarem cada jogo uma única vez).
 * @property gameSeed semente do jogo, para recriar os jogadores virtuais (`botSeed`). Nunca vai para a interface.
 * @property match jogo completo (todas as partidas, totais e a partida atual).
 * @property events eventos públicos da partida atual, em ordem.
 */
@Serializable
data class SavedGame(
    val formatVersion: Int = FORMAT_VERSION,
    val gameId: String,
    val config: SavedConfig,
    val gameSeed: Long,
    val match: Match,
    val events: List<SavedEvent>,
) {
    companion object {
        /** Versão atual do formato; arquivos de outra versão são tratados como "sem jogo salvo". */
        const val FORMAT_VERSION: Int = 1
    }
}
