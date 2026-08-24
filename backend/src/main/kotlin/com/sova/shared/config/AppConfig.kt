package com.sova.shared.config

data class AppConfig(
    val http: HttpConfig,
    val database: DatabaseConfig,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                http = HttpConfig(
                    host = environment["SOVA_HTTP_HOST"] ?: "0.0.0.0",
                    port = environment.optionalPort("SOVA_HTTP_PORT") ?: 8080,
                ),
                database = DatabaseConfig(
                    url = environment.required("DATABASE_URL"),
                    user = environment.required("DATABASE_USER"),
                    password = environment.required("DATABASE_PASSWORD"),
                ),
            )
    }
}

data class HttpConfig(
    val host: String,
    val port: Int,
)

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("Missing required environment variable: $name")

private fun Map<String, String>.optionalPort(name: String): Int? =
    get(name)?.let { rawValue ->
        rawValue.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: error("Environment variable $name must be a port between 1 and 65535")
    }
