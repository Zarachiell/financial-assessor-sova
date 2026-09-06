package com.sova.shared.infrastructure.database

import com.sova.shared.config.DatabaseConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresDatabaseIntegrationTest {
    @Test
    fun `migrates a real PostgreSQL database and reports readiness`() {
        EmbeddedPostgres.builder().start().use { postgres ->
            val config = DatabaseConfig(
                url = postgres.getJdbcUrl("postgres", "postgres"),
                user = "postgres",
                password = "",
            )
            val database = PostgresDatabase(config)

            database.migrate()
            database.migrate()

            assertTrue(database.isReady())
            DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select value from application_metadata where key = 'schema_owner'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("flyway", result.getString("value"))
                    }

                    statement.executeQuery(
                        "select count(*) from flyway_schema_history where success",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(12, result.getInt(1))
                    }

                    statement.executeQuery(
                        """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'cash_flow_entries'
                          AND column_name IN ('account_id', 'day_of_month')
                        """.trimIndent(),
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt(1))
                    }

                    statement.executeQuery(
                        """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'categories'
                          AND column_name = 'expense_nature'
                        """.trimIndent(),
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                }
            }

            DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE phase_one")
                }
            }
            val phaseOneConfig = DatabaseConfig(
                url = postgres.getJdbcUrl("postgres", "phase_one"),
                user = "postgres",
                password = "",
            )
            Flyway.configure()
                .dataSource(phaseOneConfig.url, phaseOneConfig.user, phaseOneConfig.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate()

            PostgresDatabase(phaseOneConfig).migrate()

            DriverManager.getConnection(phaseOneConfig.url, phaseOneConfig.user, phaseOneConfig.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('accounts', 'categories', 'transactions', 'cash_flow_entries')
                        """.trimIndent(),
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(4, result.getInt(1))
                    }
                }
            }
        }
    }
}
