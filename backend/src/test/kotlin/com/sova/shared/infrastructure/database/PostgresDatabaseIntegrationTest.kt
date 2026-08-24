package com.sova.shared.infrastructure.database

import com.sova.shared.config.DatabaseConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
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
                        assertEquals(3, result.getInt(1))
                    }
                }
            }
        }
    }
}
