package com.gtranca.sim

import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Simulador headless bot × bot (ver skill `simulate-matches`).
 *
 * Exemplo: `gradlew :sim:run --args="--games 1000 --seed 42 --mode duplas --sides facil,aleatorio"`.
 * Código de saída 0 sem falhas, 1 com falhas, 2 com argumentos inválidos.
 */
fun main(args: Array<String>) {
    if ("--help" in args || "-h" in args) {
        println(SimConfig.USAGE)
        return
    }
    val config = try {
        SimConfig.parse(args).also { cfg -> cfg.sides.forEach { createSimBot(it, Random(0)) } }
    } catch (e: IllegalArgumentException) {
        usageError(e.message)
    } catch (e: UnsupportedOperationException) {
        usageError(e.message)
    }

    val simulator = Simulator(config)
    val start = System.nanoTime()
    val progressStep = (config.games / 10).coerceAtLeast(1)
    val outcomes = (0 until config.games).map { i ->
        simulator.runGame(config.seed + i).also {
            if ((i + 1) % progressStep == 0) System.err.println("… ${i + 1}/${config.games} jogos")
        }
    }
    val elapsedMillis = (System.nanoTime() - start) / 1_000_000

    val report = Report(config, outcomes, elapsedMillis)
    print(report.render())
    if (report.failures.isNotEmpty()) exitProcess(1)
}

private fun usageError(message: String?): Nothing {
    System.err.println("Erro: $message")
    System.err.println(SimConfig.USAGE)
    exitProcess(2)
}
