package com.sova.shared.infrastructure.database

import com.sova.DatabaseHealth
import com.sova.shared.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.sql.DriverManager

class PostgresDatabase(
    private val config: DatabaseConfig,
) : DatabaseHealth {
    fun migrate() {
        Flyway.configure()
            .dataSource(config.url, config.user, config.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    override fun isReady(): Boolean =
        runCatching {
            DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetchValue("select 1", Int::class.java) == 1
            }
        }.getOrDefault(false)
}
