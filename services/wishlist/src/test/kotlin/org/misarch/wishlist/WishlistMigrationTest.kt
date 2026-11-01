package org.misarch.wishlist

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V2+ migrations applied to rows written by the V1 schema.
 */
class WishlistMigrationTest {

    @Test
    fun `product variant arrays become link rows`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "1")
            val wishlist = UUID.randomUUID()
            val empty = UUID.randomUUID()
            val variant1 = UUID.randomUUID()
            val variant2 = UUID.randomUUID()
            for ((id, ids) in listOf(wishlist to "ARRAY['$variant1', '$variant2', '$variant1']::uuid[]", empty to "'{}'::uuid[]")) {
                db.execute(
                    "INSERT INTO WishlistEntity (id, userId, name, createdAt, lastUpdatedAt, productVariantIds) " +
                        "VALUES ('$id', '${UUID.randomUUID()}', 'w', now(), now(), $ids)"
                )
            }
            db.migrate()

            fun variants(id: UUID) = db.query(
                "SELECT productVariantId FROM WishlistToProductVariantEntity WHERE wishlistId = '$id'"
            ) { it.getObject(1) }
            assertThat(variants(wishlist)).containsExactlyInAnyOrder(variant1, variant2)
            assertThat(variants(empty)).isEmpty()
            assertThat(db.query("INSERT INTO WishlistEntity (userId, name, createdAt, lastUpdatedAt) " +
                "VALUES (gen_random_uuid(), 'w', now(), now()) RETURNING id") { it.getObject(1) }).hasSize(1)
        }
    }
}
