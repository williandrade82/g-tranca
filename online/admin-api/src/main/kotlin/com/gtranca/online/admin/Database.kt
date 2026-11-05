package com.gtranca.online.admin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Pool de conexoes e migracoes do banco (Flyway, `db/admin`), schema proprio `admin` (nunca o mesmo schema de
 * lobby/economy: cada servico e o dono dos seus dados). As credenciais vem do ambiente (Secret no cluster).
 */
object Database {
    const val SCHEMA = "admin"

    fun connect(url: String, user: String, password: String, maxPool: Int = 5): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = maxPool
                poolName = "admin"
                // Nao falha ao criar o pool se o Postgres ainda nao subiu; a espera fica em [migrateWithRetry].
                initializationFailTimeout = -1
            },
        )

    /** Aplica as migracoes pendentes (idempotente). Historico proprio (`flyway_admin_history`) no schema `admin`. */
    fun migrate(db: DataSource) {
        Flyway.configure().dataSource(db)
            .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
            .table("flyway_admin_history")
            .locations("classpath:db/admin")
            .load().migrate()
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
