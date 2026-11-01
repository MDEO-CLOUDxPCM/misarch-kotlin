package org.misarch.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class InventorySchemaTest {

    @Test
    fun `the database generates ids and numbers the items in the order they are added`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val variant = UUID.randomUUID()
            for (status in listOf("IN_STORAGE", "RESERVED", "IN_STORAGE")) {
                db.execute(
                    "INSERT INTO ProductItemEntity (productVariantId, inventoryStatus) VALUES ('$variant', '$status')"
                )
            }

            assertThat(db.query("SELECT inventoryStatus FROM ProductItemEntity ORDER BY insertionOrder") {
                it.getString(1)
            }).containsExactly("IN_STORAGE", "RESERVED", "IN_STORAGE")
            assertThat(db.query("SELECT COUNT(DISTINCT id) FROM ProductItemEntity") { it.getInt(1) }).containsExactly(3)
        }
    }

    @Test
    fun `an item needs a product variant`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy {
                db.execute("INSERT INTO ProductItemEntity (inventoryStatus) VALUES ('IN_STORAGE')")
            }.hasMessageContaining("productvariantid")
        }
    }
}
