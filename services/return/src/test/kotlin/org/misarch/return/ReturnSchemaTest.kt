package org.misarch.`return`

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class ReturnSchemaTest {

    @Test
    fun `the database generates return ids and has no triggers (the return rules are checked by ReturnService)`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val order = UUID.randomUUID()
            db.execute("INSERT INTO OrderEntity (id, userId) VALUES ('$order', '${UUID.randomUUID()}')")
            val returnId = db.query(
                "INSERT INTO ReturnEntity (reason, orderId, refundedAmount, createdAt) VALUES ('r', '$order', 10, now()) RETURNING id"
            ) { it.getObject(1) }.single()
            db.execute("INSERT INTO OrderItemEntity (id, orderId) VALUES ('${UUID.randomUUID()}', '$order')")
            db.execute("UPDATE OrderItemEntity SET returnedWithId = '$returnId'")
            assertThat(db.query("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = 'public'") { it.getInt(1) })
                .containsExactly(0)
        }
    }

    @Test
    fun `returns and order items belong to a known order`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy {
                db.execute("INSERT INTO ReturnEntity (reason, orderId, refundedAmount, createdAt) VALUES ('r', '${UUID.randomUUID()}', 1, now())")
            }.hasMessageContaining("returnentity_orderid_fkey")
            assertThatThrownBy {
                db.execute("INSERT INTO OrderItemEntity (id, orderId) VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}')")
            }.hasMessageContaining("orderitementity_orderid_fkey")
        }
    }
}
