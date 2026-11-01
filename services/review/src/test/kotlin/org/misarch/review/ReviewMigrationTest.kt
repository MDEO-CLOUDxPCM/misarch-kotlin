package org.misarch.review

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** V3 renames the tables and columns of V1/V2 and their rating values, keeping the rows. */
class ReviewMigrationTest {

    private val db = PostgresTestDatabase

    private fun inSchema(block: (schema: String, connection: Connection) -> Unit) {
        val schema = "review_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        DriverManager.getConnection(db.jdbcUrl, db.container.username, db.container.password).use { connection ->
            try {
                block(schema, connection)
            } finally {
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }

    private fun flyway(schema: String, target: String) = Flyway.configure()
        .dataSource(db.jdbcUrl, db.container.username, db.container.password)
        .schemas(schema).locations("classpath:db/migration").target(target).load()

    private fun insertReview(connection: Connection, schema: String, id: UUID, rating: String) {
        connection.createStatement().use {
            it.execute("SET search_path TO $schema")
            it.execute(
                "INSERT INTO reviews VALUES ('$id', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', " +
                    "'b', '$rating', now(), now(), true)"
            )
        }
    }

    @Test
    fun `migration V3 keeps the rows and stores the ratings as enum names with their stars`() = inSchema { schema, connection ->
        flyway(schema, "2").migrate()
        val (one, four) = UUID.randomUUID() to UUID.randomUUID()
        insertReview(connection, schema, one, "OneStars")
        insertReview(connection, schema, four, "FourStars")
        connection.createStatement().use {
            it.execute("SET search_path TO $schema")
            it.execute("INSERT INTO product_variants VALUES ('$one', '$four')")
        }
        flyway(schema, "latest").migrate()
        connection.createStatement().use { s ->
            s.execute("SET search_path TO $schema")
            val rows = s.executeQuery("SELECT id, rating, stars, isVisible FROM ReviewEntity ORDER BY stars").use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getBoolean(4))) }
            }
            assertThat(rows).containsExactly(listOf(one, "ONE_STARS", 1, true), listOf(four, "FOUR_STARS", 4, true))
            val productId = s.executeQuery("SELECT productId FROM ProductVariantEntity WHERE id = '$one'").use { rs ->
                rs.next(); rs.getObject(1, UUID::class.java)
            }
            assertThat(productId).isEqualTo(four)
        }
    }

    @Test
    fun `migration V3 fails on an unknown rating`() = inSchema { schema, connection ->
        flyway(schema, "2").migrate()
        insertReview(connection, schema, UUID.randomUUID(), "SixStars")
        assertThatThrownBy { flyway(schema, "latest").migrate() }.hasMessageContaining("reviewentity_rating_check")
    }
}
