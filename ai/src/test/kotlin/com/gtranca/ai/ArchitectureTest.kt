package com.gtranca.ai

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guarda arquitetural (CLAUDE.md, "Princípios do motor"): o bot só recebe o `PlayerView` do seu assento,
 * nunca o estado completo da partida.
 *
 * **A garantia principal é a assinatura de `BotPlayer`**: `chooseAction(view, legal)` e `observe(evento
 * público)` não recebem estado nenhum, então nenhum bot tem como obter o estado real. Este teste é uma
 * defesa textual complementar (não um verificador de tipos): procura nomes nos fontes, inclusive em
 * comentários, para não haver brecha por import com curinga ou nome qualificado. O que ele verifica:
 *
 * 1. **Nenhum** arquivo de produção do `:ai` cita as vias para o estado verdadeiro: a projeção do estado para
 *    um assento (a função de vista do motor), a distribuição (`dealRound`, `dealFromOrderedDeck`) e o jogo
 *    (`Match`, `startMatch`, `startNextRound`).
 * 2. Fora do pacote `com.gtranca.ai.hard`, vale a regra antiga: nada de `RoundState` nem `RoundEngine`, e
 *    também nada de `determinize` (sortear mundos é assunto só da busca).
 * 3. No pacote `hard` (busca ISMCTS do bot Difícil), `RoundState` e `RoundEngine` são permitidos, mas um
 *    `RoundState` só deve SURGIR de `PlayerView.determinize` (um mundo sorteado a partir da vista) ou de
 *    `RoundEngine.apply` sobre um estado assim. São proibidos no pacote os outros meios de criar ou alterar
 *    um `RoundState`: o construtor (`RoundState(`, `::RoundState`), `copy(` de qualquer objeto (com ou sem
 *    receptor explícito, por simplicidade), a desserialização (`RoundState.serializer`, `Json`, `Cbor`,
 *    `ProtoBuf`, `decodeFrom…`, `ObjectInputStream`) e reflexão. E o pacote tem de usar `determinize`.
 *    Formas muito indiretas (ex.: um alias de tipo) não são pegas pela busca textual; por isso a garantia
 *    de fato é a assinatura de `BotPlayer`.
 */
class ArchitectureTest {

    // O Gradle roda os testes com o diretório do módulo como diretório de trabalho.
    private val mainDir = File("src/main")
    private val hardDir = File(mainDir, "kotlin/com/gtranca/ai/hard")

    private fun sources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java") }.toList()

    private fun isHard(file: File): Boolean = file.canonicalPath.startsWith(hardDir.canonicalPath + File.separator)

    private fun violations(files: List<File>, forbidden: Regex): List<String> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line ->
            forbidden.find(line)?.let { "${file.invariantSeparatorsPath}:${i + 1}: ${it.value}" }
        }
    }

    @Test
    fun `nenhum codigo de producao do ai usa as vias para o estado verdadeiro`() {
        mainDir.isDirectory shouldBe true
        val all = sources(mainDir)
        all.size shouldBeGreaterThan 0
        violations(all, TRUE_STATE_ROUTES).shouldBeEmpty()
    }

    @Test
    fun `fora do pacote hard o ai nao referencia RoundState, RoundEngine nem determinize`() {
        val outside = sources(mainDir).filterNot(::isHard)
        outside.size shouldBeGreaterThan 0
        violations(outside, ENGINE_STATE).shouldBeEmpty()
    }

    @Test
    fun `no pacote hard um RoundState so surge de determinize ou de RoundEngine apply`() {
        hardDir.isDirectory shouldBe true
        val hard = sources(hardDir)
        hard.size shouldBeGreaterThan 0
        violations(hard, STATE_FACTORIES).shouldBeEmpty()
        withClue("o pacote hard deve sortear os mundos com determinize") {
            hard.filter { f -> f.readLines().any { DETERMINIZE.containsMatchIn(it) } }.shouldNotBeEmpty()
        }
    }

    private companion object {
        val ENGINE_STATE = Regex("""\b(RoundState|RoundEngine|determinize)\b""")
        val TRUE_STATE_ROUTES = Regex("""\b(viewFor|dealRound|dealFromOrderedDeck|startMatch|startNextRound|Match)\b""")
        val STATE_FACTORIES = Regex(
            """\bRoundState\s*\(|::\s*RoundState\b|\bRoundState\s*\.\s*serializer|\bcopy\s*\(|\bJson\b|\bCbor\b|\bProtoBuf\b|""" +
                """\bdecodeFrom\w*|\bObjectInputStream\b|::class\.java|\bClass\.forName\b|\bkotlin\.reflect\b|\bjava\.lang\.reflect\b""",
        )
        val DETERMINIZE = Regex("""\.determinize\s*\(""")
    }
}
