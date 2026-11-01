package org.misarch.shoppingcart

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class ShoppingcartSchemaTest {

    @Test
    fun `the database generates item ids, an item belongs to a known user, its variant need not be known`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val user = UUID.randomUUID()
            db.execute("INSERT INTO UserEntity (id, shoppingcartLastUpdatedAt) VALUES ('$user', now())")
            db.execute(
                "INSERT INTO ShoppingCartItemEntity (userId, count, addedAt, productVariantId) VALUES ('$user', 1, now(), gen_random_uuid())"
            )
            assertThat(db.query("SELECT COUNT(*) FROM ShoppingCartItemEntity WHERE userId = '$user'") { it.getInt(1) }).containsExactly(1)
            assertThatThrownBy {
                db.execute(
                    "INSERT INTO ShoppingCartItemEntity (userId, count, addedAt, productVariantId) " +
                        "VALUES ('${UUID.randomUUID()}', 1, now(), gen_random_uuid())"
                )
            }.hasMessageContaining("shoppingcartitementity_userid_fkey")
        }
    }
}
