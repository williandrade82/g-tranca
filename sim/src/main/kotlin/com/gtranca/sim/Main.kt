package com.gtranca.sim

import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Simulador headless bot × bot (ver skill `simulate-matches`).
 *
 * Exemplo: `gradlew :sim:run --args="--games 1000 --seed 42 --mode duplas --sides facil,aleatorio"`.
 * Código de saída 0 sem falhas, 1 com falhas, 2 com argumentos inválidos.
 */
fun main(args: Array<String>) {
    val code = runSimulation(args)
    if (code != 0) exitProcess(code)
}

/**
 * Roda a simulação descrita por [args] e devolve o código de saída: 0 sem falhas, 1 com falhas, 2 com
 * argumentos inválidos (mensagem e uso em [err], sem stack trace). O relatório vai para [out].
 */
internal fun runSimulation(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    if ("--help" in args || "-h" in args) {
        out.println(SimConfig.USAGE)
        return 0
    }
    val config = try {
        // cria um bot de cada tipo para validar já aqui as opções (inclusive as do dificil)
        SimConfig.parse(args).also { cfg -> cfg.sides.forEach { createSimBot(it, Random(0), cfg.hardConfig) } }
    } catch (e: IllegalArgumentException) {
        return usageError(err, e.message)
    } catch (e: UnsupportedOperationException) {
        return usageError(err, e.message)
    }

    val simulator = Simulator(config)
    val start = System.nanoTime()
    val progressStep = (config.games / 10).coerceAtLeast(1)
    val done = AtomicInteger()
    fun run(i: Int): GameOutcome = simulator.runGame(config.seed + i).also {
        val n = done.incrementAndGet()
        if (n % progressStep == 0) err.println("… $n/${config.games} jogos")
    }
    // Cada jogo é independente (bots e RNG próprios): rodar em paralelo não muda os resultados.
    val outcomes = if (config.threads == 1) {
        (0 until config.games).map(::run)
    } else {
        val pool = Executors.newFixedThreadPool(config.threads)
        try {
            (0 until config.games).map { i -> pool.submit<GameOutcome> { run(i) } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
    val elapsedMillis = (System.nanoTime() - start) / 1_000_000

    val report = Report(config, outcomes, elapsedMillis)
    out.print(report.render())
    return if (report.failures.isNotEmpty()) 1 else 0
}

private fun usageError(err: PrintStream, message: String?): Int {
    err.println("Erro: $message")
    err.println(SimConfig.USAGE)
    return 2
}
