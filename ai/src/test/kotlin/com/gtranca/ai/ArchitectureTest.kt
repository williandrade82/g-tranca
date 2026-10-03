package com.gtranca.ai

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guarda arquitetural (CLAUDE.md, "Princípios do motor"): o bot só recebe o `PlayerView` do seu
 * assento, nunca o estado completo da partida. Nenhum arquivo de produção do `:ai` pode importar ou
 * referenciar `RoundState` nem `RoundEngine` (nem em comentários, para não haver brecha por import
 * com curinga ou nome qualificado).
 */
class ArchitectureTest {

    @Test
    fun `codigo de producao do ai nao referencia RoundState nem RoundEngine`() {
        // O Gradle roda os testes com o diretório do módulo como diretório de trabalho.
        val mainDir = File("src/main")
        mainDir.isDirectory shouldBe true
        val sources = mainDir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java") }.toList()
        sources.size shouldBeGreaterThan 0

        val violations = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                FORBIDDEN.find(line)?.let { "${file.invariantSeparatorsPath}:${i + 1}: ${it.value}" }
            }
        }
        violations.shouldBeEmpty()
    }

    private companion object {
        val FORBIDDEN = Regex("""\b(RoundState|RoundEngine)\b""")
    }
}
