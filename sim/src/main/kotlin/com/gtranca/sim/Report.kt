package com.gtranca.sim

import java.util.Locale
import kotlin.math.sqrt

/** Resumo agregado dos jogos simulados. */
class Report(private val config: SimConfig, private val outcomes: List<GameOutcome>, private val elapsedMillis: Long) {

    val completed: List<GameOutcome> = outcomes.filter { it.failure == null }
    val failures: List<Failure> = outcomes.mapNotNull { it.failure }

    fun render(maxFailuresListed: Int = 50): String = buildString {
        val sideCount = config.mode.sideCount
        appendLine("=== Simulação G-Tranca ===")
        appendLine(
            "Modo: ${config.mode.name.lowercase()} | lados: ${config.sides.mapIndexed { i, s -> "lado $i=$s" }.joinToString(", ")}" +
                " | alvo: ${config.target} | semente base: ${config.seed} | invariantes extras: ${if (config.checkInvariants) "sim" else "não"}",
        )
        appendLine("Jogos: ${outcomes.size} | concluídos: ${completed.size} | com falha: ${failures.size}")
        appendLine()

        appendLine("Vitórias por lado (jogos concluídos, IC 95%):")
        val n = completed.size
        for (side in 0 until sideCount) {
            val wins = completed.count { it.winner?.index == side }
            appendLine("  lado $side (${config.sides[side]}): $wins${if (n > 0) " = ${pct(wins, n)} ± ${fmt(ci95(wins, n) * 100)} p.p." else ""}")
        }
        appendLine()

        val rounds = completed.flatMap { it.rounds }
        val noWinner = rounds.count { it.winner == null }
        appendLine("Partidas por jogo: média ${avg(completed.map { it.rounds.size.toDouble() })}, máx ${completed.maxOfOrNull { it.rounds.size } ?: 0}")
        appendLine("Jogadas por partida: média ${avg(rounds.map { it.turns.toDouble() })}, máx ${rounds.maxOfOrNull { it.turns } ?: 0}")
        appendLine("Ações por partida: média ${avg(rounds.map { it.actions.toDouble() })}, máx ${rounds.maxOfOrNull { it.actions } ?: 0}")
        appendLine("Partidas sem vencedor (§11.2): $noWinner de ${rounds.size}${if (rounds.isNotEmpty()) " (${pct(noWinner, rounds.size)})" else ""}")
        for (side in 0 until sideCount) {
            val batidas = rounds.count { it.winner?.index == side }
            appendLine("  batidas do lado $side: $batidas")
        }
        appendLine("Pontuação final média: " + (0 until sideCount).joinToString(" | ") { side ->
            "lado $side ${avg(completed.map { it.totals[side].toDouble() })}"
        })
        appendLine()

        appendLine("Tempo total: ${fmt(elapsedMillis / 1000.0)} s (${fmt(elapsedMillis.toDouble() / outcomes.size.coerceAtLeast(1))} ms por jogo)")
        appendLine()

        if (failures.isEmpty()) {
            appendLine("Falhas: nenhuma")
        } else {
            appendLine("Falhas por tipo:")
            failures.groupBy { it.kind }.toSortedMap().forEach { (kind, list) ->
                appendLine("  $kind: ${list.size} (sementes: ${list.take(10).joinToString { it.gameSeed.toString() }}${if (list.size > 10) ", …" else ""})")
            }
            appendLine("Detalhes (reproduza com --games 1 --seed <semente> e os mesmos --mode/--sides/--target):")
            failures.take(maxFailuresListed).forEach { f ->
                appendLine("  [${f.kind}] semente ${f.gameSeed}, partida ${f.round}, ação #${f.actionIndex}: ${f.action ?: "-"} :: ${f.message}")
            }
            if (failures.size > maxFailuresListed) appendLine("  … e mais ${failures.size - maxFailuresListed}")
        }
    }

    private fun ci95(successes: Int, n: Int): Double {
        val p = successes.toDouble() / n
        return 1.96 * sqrt(p * (1 - p) / n)
    }

    private fun pct(part: Int, whole: Int): String = "${fmt(part * 100.0 / whole)}%"

    private fun avg(values: List<Double>): String = if (values.isEmpty()) "-" else fmt(values.average())

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
