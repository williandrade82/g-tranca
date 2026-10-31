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
            },
        )

    /** Aplica as migrações pendentes (idempotente). */
    fun migrate(db: DataSource) {
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate()
    }
}
