package com.gtranca.online.economy

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Pool de conexões e migrações do banco (Flyway, `db/migration`). As credenciais vêm do ambiente (Secret no cluster). */
object Database {
    fun connect(url: String, user: String, password: String, maxPool: Int = 10): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = maxPool
                poolName = "economy"
                // Nao falha ao criar o pool se o Postgres ainda nao subiu; a espera fica em [migrateWithRetry].
                initializationFailTimeout = -1
            },
        )

    /** Aplica as migrações pendentes (idempotente). */
    fun migrate(db: DataSource) {
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate()
    }

    /** Migra esperando o banco: tenta de novo com espera crescente (ate [maxDelayMs]) sem derrubar a JVM. */
    fun migrateWithRetry(db: DataSource, sleep: (Long) -> Unit = Thread::sleep, maxDelayMs: Long = 30_000L) =
        retryForever(sleep, maxDelayMs) { migrate(db) }

    /** Repete [block] ate dar certo, com espera dobrando a partir de 1 s, limitada a [maxDelayMs]. */
    fun <T> retryForever(sleep: (Long) -> Unit, maxDelayMs: Long, block: () -> T): T {
        var delay = 1_000L
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                System.err.println("AVISO: banco indisponivel (${e.javaClass.simpleName}); nova tentativa em ${delay / 1000}s")
                sleep(delay)
                delay = (delay * 2).coerceAtMost(maxDelayMs)
            }
        }
    }
}
