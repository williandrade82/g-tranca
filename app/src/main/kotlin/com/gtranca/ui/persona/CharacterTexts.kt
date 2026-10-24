package com.gtranca.ui.persona

import androidx.annotation.StringRes
import com.gtranca.R
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile

/** §14.5 característica em poucas palavras (o estilo de jogo do perfil). */
@StringRes
fun BotProfile.tagRes(): Int = when (this) {
    BotProfile.JOVEM -> R.string.char_jovem_tag
    BotProfile.SOBRIO -> R.string.char_sobrio_tag
    BotProfile.ALTERNATIVO -> R.string.char_alternativo_tag
    BotProfile.ATLETA -> R.string.char_atleta_tag
}

/** §14.5 bordão do robô, texto decorativo sob o seu retrato. */
@StringRes
fun BotProfile.mottoRes(): Int = when (this) {
    BotProfile.JOVEM -> R.string.char_jovem_motto
    BotProfile.SOBRIO -> R.string.char_sobrio_motto
    BotProfile.ALTERNATIVO -> R.string.char_alternativo_motto
    BotProfile.ATLETA -> R.string.char_atleta_motto
}

/** §14.5 texto de apresentação do perfil, no painel de seleção de mesas. */
@StringRes
fun BotProfile.pitchRes(): Int = when (this) {
    BotProfile.JOVEM -> R.string.char_jovem_pitch
    BotProfile.SOBRIO -> R.string.char_sobrio_pitch
    BotProfile.ALTERNATIVO -> R.string.char_alternativo_pitch
    BotProfile.ATLETA -> R.string.char_atleta_pitch
}

/** §14.5 nome do nível (Iniciante, Maduro, Sênior). */
@StringRes
fun BotLevel.labelRes(): Int = when (this) {
    BotLevel.INICIANTE -> R.string.level_iniciante
    BotLevel.MADURO -> R.string.level_maduro
    BotLevel.SENIOR -> R.string.level_senior
}
