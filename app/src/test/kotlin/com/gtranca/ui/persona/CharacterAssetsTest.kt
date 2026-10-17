package com.gtranca.ui.persona

import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * §14.5 as 48 imagens dos personagens existem com o tamanho e o peso combinados (576x384 nas poses grandes, 256x256 no
 * pensador). A conferência visual (cor da pele, recorte, fundo) está em `tools/conferir_personagens.py`.
 */
class CharacterAssetsTest {
    private val dir = File("src/main/res/drawable-nodpi")

    private fun file(profile: BotProfile, level: BotLevel, pose: Pose) =
        File(dir, "char_${profile.code}_${level.code}_${pose.code}.webp")

    /** Largura e altura do cabeçalho WebP (VP8X, VP8L ou VP8). */
    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        fun le(offset: Int, count: Int) = (0 until count).sumOf { (bytes[offset + it].toInt() and 0xFF) shl (8 * it) }
        String(bytes, 0, 4) shouldBe "RIFF"
        String(bytes, 8, 4) shouldBe "WEBP"
        return when (String(bytes, 12, 4)) {
            "VP8X" -> (le(24, 3) + 1) to (le(27, 3) + 1)
            "VP8L" -> {
                val bits = le(21, 4)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8 " -> (le(26, 2) and 0x3FFF) to (le(28, 2) and 0x3FFF)
            else -> error("Formato WebP desconhecido")
        }
    }

    @Test
    fun `§14_5 cada personagem tem as 4 poses com o tamanho combinado`() {
        val problems = mutableListOf<String>()
        for (profile in BotProfile.entries) for (level in BotLevel.entries) for (pose in Pose.entries) {
            val f = file(profile, level, pose)
            if (!f.exists()) {
                problems += "${f.name}: não existe"
                continue
            }
            val expected = if (pose == Pose.PENSADOR) 256 to 256 else 576 to 384
            val actual = dimensions(f.readBytes())
            if (actual != expected) problems += "${f.name}: ${actual.first}x${actual.second}, esperado ${expected.first}x${expected.second}"
            if (actual.first.toFloat() / actual.second != pose.aspectRatio) problems += "${f.name}: proporção diferente da usada pela tela"
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `§14_5 peso das imagens dentro do orcamento`() {
        val sizes = BotProfile.entries.flatMap { p -> BotLevel.entries.flatMap { l -> Pose.entries.map { file(p, l, it).length() } } }
        (sizes.max() / 1024).toInt() shouldBeLessThan 150
        (sizes.sum() / 1024).toInt() shouldBeLessThan 4_096
    }

    @Test
    fun `§14_5 os originais ficam em docs, um por perfil e nivel`() {
        val originals = File("../docs/personagens").listFiles { f -> f.extension == "png" }.orEmpty()
        originals.size shouldBe BotProfile.entries.size * BotLevel.entries.size
    }
}
