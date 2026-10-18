package com.gtranca.ui.persona

import androidx.annotation.DrawableRes
import com.gtranca.R
import com.gtranca.game.BotCharacter
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile

/** §14.5 as 4 poses de cada personagem. */
enum class Pose(val code: String) {
    APRESENTACAO("apresentacao"),
    VENCEDOR("vencedor"),
    PERDEDOR("perdedor"),
    PENSADOR("pensador"),
}

/**
 * Imagem de um personagem numa pose (§14.5): 576x384 (3:2) nas poses grandes e 256x256 no pensador.
 * Mapa explícito (e não `getIdentifier`) para o encolhimento de recursos do release não descartar as imagens.
 */
@DrawableRes
fun characterRes(character: BotCharacter, pose: Pose): Int = IMAGES.getValue(Triple(character.profile, character.level, pose))

/** Todas as combinações com imagem (48), para os testes conferirem a cobertura. */
internal val IMAGES: Map<Triple<BotProfile, BotLevel, Pose>, Int> = mapOf(
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.APRESENTACAO) to R.drawable.char_jovem_iniciante_apresentacao,
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_jovem_iniciante_vencedor,
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_jovem_iniciante_perdedor,
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.PENSADOR) to R.drawable.char_jovem_iniciante_pensador,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.APRESENTACAO) to R.drawable.char_jovem_maduro_apresentacao,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_jovem_maduro_vencedor,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_jovem_maduro_perdedor,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.PENSADOR) to R.drawable.char_jovem_maduro_pensador,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.APRESENTACAO) to R.drawable.char_jovem_senior_apresentacao,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_jovem_senior_vencedor,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_jovem_senior_perdedor,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.PENSADOR) to R.drawable.char_jovem_senior_pensador,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.APRESENTACAO) to R.drawable.char_sobrio_iniciante_apresentacao,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_sobrio_iniciante_vencedor,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_sobrio_iniciante_perdedor,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.PENSADOR) to R.drawable.char_sobrio_iniciante_pensador,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.APRESENTACAO) to R.drawable.char_sobrio_maduro_apresentacao,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_sobrio_maduro_vencedor,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_sobrio_maduro_perdedor,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.PENSADOR) to R.drawable.char_sobrio_maduro_pensador,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.APRESENTACAO) to R.drawable.char_sobrio_senior_apresentacao,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_sobrio_senior_vencedor,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_sobrio_senior_perdedor,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.PENSADOR) to R.drawable.char_sobrio_senior_pensador,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.APRESENTACAO) to R.drawable.char_alternativo_iniciante_apresentacao,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_alternativo_iniciante_vencedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_alternativo_iniciante_perdedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.PENSADOR) to R.drawable.char_alternativo_iniciante_pensador,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.APRESENTACAO) to R.drawable.char_alternativo_maduro_apresentacao,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_alternativo_maduro_vencedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_alternativo_maduro_perdedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.PENSADOR) to R.drawable.char_alternativo_maduro_pensador,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.APRESENTACAO) to R.drawable.char_alternativo_senior_apresentacao,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_alternativo_senior_vencedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_alternativo_senior_perdedor,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.PENSADOR) to R.drawable.char_alternativo_senior_pensador,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.APRESENTACAO) to R.drawable.char_atleta_iniciante_apresentacao,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_atleta_iniciante_vencedor,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_atleta_iniciante_perdedor,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.PENSADOR) to R.drawable.char_atleta_iniciante_pensador,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.APRESENTACAO) to R.drawable.char_atleta_maduro_apresentacao,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_atleta_maduro_vencedor,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_atleta_maduro_perdedor,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.PENSADOR) to R.drawable.char_atleta_maduro_pensador,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.APRESENTACAO) to R.drawable.char_atleta_senior_apresentacao,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_atleta_senior_vencedor,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_atleta_senior_perdedor,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.PENSADOR) to R.drawable.char_atleta_senior_pensador,
)

/**
 * Recorte quadrado do rosto (256x256) de um personagem no vencedor ou no perdedor, para os avatares pequenos das
 * tabelas de pontos (§14.5). No pensador, a própria imagem da pose já é quadrada.
 */
@DrawableRes
fun avatarRes(character: BotCharacter, pose: Pose): Int {
    require(pose != Pose.APRESENTACAO) { "A apresentação não tem rosto quadrado" }
    return if (pose == Pose.PENSADOR) characterRes(character, pose) else FACES.getValue(Triple(character.profile, character.level, pose))
}

/** Rostos quadrados do vencedor e do perdedor (24), para os testes conferirem a cobertura. */
internal val FACES: Map<Triple<BotProfile, BotLevel, Pose>, Int> = mapOf(
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_jovem_iniciante_vencedor_av,
        Triple(BotProfile.JOVEM, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_jovem_iniciante_perdedor_av,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_jovem_maduro_vencedor_av,
        Triple(BotProfile.JOVEM, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_jovem_maduro_perdedor_av,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_jovem_senior_vencedor_av,
        Triple(BotProfile.JOVEM, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_jovem_senior_perdedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_sobrio_iniciante_vencedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_sobrio_iniciante_perdedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_sobrio_maduro_vencedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_sobrio_maduro_perdedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_sobrio_senior_vencedor_av,
        Triple(BotProfile.SOBRIO, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_sobrio_senior_perdedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_alternativo_iniciante_vencedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_alternativo_iniciante_perdedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_alternativo_maduro_vencedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_alternativo_maduro_perdedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_alternativo_senior_vencedor_av,
        Triple(BotProfile.ALTERNATIVO, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_alternativo_senior_perdedor_av,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.VENCEDOR) to R.drawable.char_atleta_iniciante_vencedor_av,
        Triple(BotProfile.ATLETA, BotLevel.INICIANTE, Pose.PERDEDOR) to R.drawable.char_atleta_iniciante_perdedor_av,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.VENCEDOR) to R.drawable.char_atleta_maduro_vencedor_av,
        Triple(BotProfile.ATLETA, BotLevel.MADURO, Pose.PERDEDOR) to R.drawable.char_atleta_maduro_perdedor_av,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.VENCEDOR) to R.drawable.char_atleta_senior_vencedor_av,
        Triple(BotProfile.ATLETA, BotLevel.SENIOR, Pose.PERDEDOR) to R.drawable.char_atleta_senior_perdedor_av,
)
