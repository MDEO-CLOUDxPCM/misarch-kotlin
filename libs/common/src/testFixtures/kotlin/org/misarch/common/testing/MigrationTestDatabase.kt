package org.misarch.common.testing

import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * A fresh, empty database in the shared [PostgresTestDatabase] container for testing a migration against rows
 * written by the previous schema:
 * ```
 * MigrationTestDatabase().use { db ->
 *     db.migrate(target = "1.6")
 *     db.execute("INSERT INTO ProductToCategoryEntity (id, productId, categoryId) VALUES (...)")
 *     db.migrate()
 *     assertThat(db.query("SELECT COUNT(*) FROM ProductToCategoryEntity") { it.getInt(1) }).containsExactly(1)
 * }
 * ```
 *
 * @param locations Flyway locations of the service's migrations
 */
class MigrationTestDatabase(private val locations: String = "classpath:db/migration") : AutoCloseable {

    private val container = PostgresTestDatabase.container

    /** Name of the database (dropped by [close]). */
    val name: String = "migration_" + UUID.randomUUID().toString().replace("-", "")

    /** JDBC URL of the database. */
    val jdbcUrl: String = container.jdbcUrl.replace("/${container.databaseName}", "/$name")

    init {
        admin { it.createStatement().execute("CREATE DATABASE $name") }
    }

    /** Applies the migrations up to [target] (a version like `"1.6"`; null: all). */
    fun migrate(target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, container.username, container.password)
            .locations(locations)
        if (target != null) {
            configuration.target(target)
        }
        configuration.load().migrate()
    }

    /** Executes [sql] (one or more statements). */
    fun execute(sql: String) {
        connection { it.createStatement().execute(sql) }
    }

    /** Runs [sql] and maps every row. */
    fun <T> query(sql: String, map: (ResultSet) -> T): List<T> = connection { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) map(rows) else null }.toList()
        }
    }

    override fun close() {
        admin { it.createStatement().execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
    }

    private fun <T> connection(block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, container.username, container.password).use(block)

    private fun <T> admin(block: (Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)
}
