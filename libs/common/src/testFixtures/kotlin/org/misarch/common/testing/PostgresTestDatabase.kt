package org.misarch.common.testing

import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One Postgres container per test JVM (started lazily, reused by all test classes of a module, removed by
 * Testcontainers' Ryuk when the JVM exits). Image: `postgres:16-alpine` (the version of the deployment).
 * Every test class should clean the tables it uses (or use unique ids) because the database is shared.
 */
object PostgresTestDatabase {

    const val IMAGE = "postgres:16-alpine"

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("misarch")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres", "-c", "fsync=off", "-c", "shared_buffers=32MB", "-c", "max_connections=50")
            .also { it.start() }
    }

    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.getMappedPort(5432)}/${container.databaseName}"

    val jdbcUrl: String get() = container.jdbcUrl
}
