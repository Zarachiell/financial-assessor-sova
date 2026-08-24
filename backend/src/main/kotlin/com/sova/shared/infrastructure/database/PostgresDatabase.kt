package com.sova.shared.infrastructure.database

import com.sova.DatabaseHealth
import com.sova.shared.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.sql.Connection
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

    fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(config.url, config.user, config.password).use(block)

    fun <T> inTransaction(block: (Connection) -> T): T =
        withConnection { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (cause: Throwable) {
                connection.rollback()
                throw cause
            }
        }

    override fun isReady(): Boolean =
        runCatching {
            withConnection { connection ->
                DSL.using(connection, SQLDialect.POSTGRES).fetchValue("select 1", Int::class.java) == 1
            }
        }.getOrDefault(false)
}
