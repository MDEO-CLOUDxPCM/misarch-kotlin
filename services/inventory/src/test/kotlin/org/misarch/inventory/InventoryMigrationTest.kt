package org.misarch.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import java.sql.DriverManager
import java.util.UUID

/**
 * V3 (UUID ids) applied to rows written with the V2 schema, each case in its own database.
 */
class InventoryMigrationTest {

    private fun database(): String {
        val name = "inventory_migration_" + UUID.randomUUID().toString().replace("-", "")
        connection(PostgresTestDatabase.jdbcUrl).use { it.createStatement().execute("CREATE DATABASE $name") }
        return PostgresTestDatabase.jdbcUrl.replace("/misarch", "/$name")
    }

    private fun connection(url: String) = DriverManager.getConnection(url, "postgres", "postgres")

    private fun flyway(url: String, target: String) = Flyway.configure()
        .dataSource(url, "postgres", "postgres")
        .locations("classpath:db/migration")
        .target(target)
        .load()

    @Test
    fun `V3 converts the ids and keeps the items`() {
        val url = database()
        flyway(url, "2").migrate()
        val variant = UUID.randomUUID()
        val order = UUID.randomUUID()
        val items = listOf(UUID.randomUUID(), UUID.randomUUID())
        connection(url).use {
            it.createStatement().execute(
                """
                INSERT INTO ProductVariantPartialEntity (id) VALUES ('$variant');
                INSERT INTO ProductItemEntity (id, productVariant, inventoryStatus, orderId)
                    VALUES ('${items[0]}', '$variant', 'IN_STORAGE', NULL), ('${items[1]}', '$variant', 'RESERVED', '$order');
                INSERT INTO ProductItemEntity (id, productVariant, inventoryStatus) VALUES ('${UUID.randomUUID()}', NULL, 'IN_STORAGE');
                """.trimIndent()
            )
        }
        flyway(url, "latest").migrate()
        connection(url).use {
            val rows = it.createStatement().executeQuery(
                "SELECT id, productVariantId, inventoryStatus, orderId FROM ProductItemEntity ORDER BY seq"
            )
            val result = generateSequence { if (rows.next()) rows else null }.map {
                listOf(rows.getObject(1), rows.getObject(2), rows.getString(3), rows.getObject(4))
            }.toList()
            assertThat(result).containsExactly(
                listOf(items[0], variant, "IN_STORAGE", null),
                listOf(items[1], variant, "RESERVED", order),
            )
            val variants = it.createStatement().executeQuery("SELECT id FROM ProductVariantEntity")
            assertThat(variants.next()).isTrue()
            assertThat(variants.getObject(1)).isEqualTo(variant)
        }
    }

    @Test
    fun `V3 fails on ids that are not UUIDs`() {
        val url = database()
        flyway(url, "2").migrate()
        connection(url).use {
            it.createStatement().execute(
                "INSERT INTO ProductItemEntity (id, productVariant, inventoryStatus) VALUES ('item-1', '${UUID.randomUUID()}', 'IN_STORAGE')"
            )
        }
        assertThatThrownBy { flyway(url, "latest").migrate() }
            .hasMessageContaining("ProductItemEntity contains ids that are not UUIDs")
    }
}
