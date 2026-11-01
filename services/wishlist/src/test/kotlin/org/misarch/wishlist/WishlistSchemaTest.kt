package org.misarch.wishlist

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class WishlistSchemaTest {

    @Test
    fun `the database generates wishlist ids and a variant is listed once per wishlist`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val wishlist = db.query(
                "INSERT INTO WishlistEntity (userId, name, createdAt, lastUpdatedAt) " +
                    "VALUES ('${UUID.randomUUID()}', 'w', now(), now()) RETURNING id"
            ) { it.getObject(1) }.single()
            val variant = UUID.randomUUID()
            db.execute("INSERT INTO WishlistToProductVariantEntity (wishlistId, productVariantId) VALUES ('$wishlist', '$variant')")

            assertThatThrownBy {
                db.execute("INSERT INTO WishlistToProductVariantEntity (wishlistId, productVariantId) VALUES ('$wishlist', '$variant')")
            }.hasMessageContaining("wishlisttoproductvariantentity_pkey")
            assertThat(db.query("SELECT COUNT(*) FROM WishlistToProductVariantEntity") { it.getInt(1) }).containsExactly(1)
        }
    }
}
