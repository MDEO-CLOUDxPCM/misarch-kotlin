package org.misarch.wishlist

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import java.sql.DriverManager
import java.util.UUID

/** V2 turns the `productVariantIds` array of V1 (rows written by an earlier version of the port) into link rows. */
class WishlistMigrationTest {

    @Test
    fun `migration V2 moves the product variant arrays into the link table`() {
        val db = PostgresTestDatabase
        val schema = "wishlist_migration_" + UUID.randomUUID().toString().replace("-", "").take(8)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(db.jdbcUrl, db.container.username, db.container.password)
            .schemas(schema).locations("classpath:db/migration").target(target).load()
        flyway("1").migrate()
        val wishlist = UUID.randomUUID()
        val empty = UUID.randomUUID()
        val (pv1, pv2) = UUID.randomUUID() to UUID.randomUUID()
        DriverManager.getConnection(db.jdbcUrl, db.container.username, db.container.password).use { connection ->
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                for ((id, ids) in listOf(wishlist to "ARRAY['$pv1', '$pv2', '$pv1']::uuid[]", empty to "'{}'::uuid[]")) {
                    s.execute(
                        "INSERT INTO WishlistEntity (id, userId, name, createdAt, lastUpdatedAt, productVariantIds) " +
                            "VALUES ('$id', '${UUID.randomUUID()}', 'w', now(), now(), $ids)"
                    )
                }
            }
            flyway("latest").migrate()
            connection.createStatement().use { s ->
                s.execute("SET search_path TO $schema")
                fun variants(id: UUID) = s.executeQuery(
                    "SELECT productVariantId FROM WishlistToProductVariantEntity WHERE wishlistId = '$id'"
                ).use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                assertThat(variants(wishlist)).containsExactlyInAnyOrder(pv1, pv2)
                assertThat(variants(empty)).isEmpty()
                s.execute("DROP SCHEMA $schema CASCADE")
            }
        }
    }
}
