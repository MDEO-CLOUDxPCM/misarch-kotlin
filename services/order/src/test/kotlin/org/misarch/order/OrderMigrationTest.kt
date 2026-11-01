package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V2+ migrations applied to rows written by the V1 schema.
 */
class OrderMigrationTest {

    @Test
    fun `id arrays become rows and the tables keep their rows under the new names`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "1")
            val user = UUID.randomUUID()
            val address1 = UUID.randomUUID()
            val address2 = UUID.randomUUID()
            val order = UUID.randomUUID()
            val compensation = UUID.randomUUID()
            val item1 = UUID.randomUUID()
            val item2 = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO users (id, user_address_ids) VALUES ('$user', ARRAY['$address1', '$address2', '$address1']::uuid[]);
                INSERT INTO orders (id, user_id, created_at, order_status, shipment_address_id, invoice_address_id,
                    compensatable_order_amount, payment_information_id)
                    VALUES ('$order', '$user', now(), 'PENDING', '$address1', '$address1', 7, '$address2');
                INSERT INTO order_compensations (id, order_id, order_item_ids, triggered_at, amount_to_compensate)
                    VALUES ('$compensation', '$order', ARRAY['$item2', '$item1']::uuid[], now(), 5);
                """.trimIndent()
            )
            db.migrate()

            assertThat(db.query("SELECT id FROM UserAddressEntity WHERE userId = '$user'") { it.getObject(1) })
                .containsExactlyInAnyOrder(address1, address2)
            assertThat(db.query(
                "SELECT orderItemId FROM OrderCompensationToOrderItemEntity " +
                    "WHERE orderCompensationId = '$compensation' ORDER BY position"
            ) { it.getObject(1) }).containsExactly(item2, item1)
            assertThat(db.query("SELECT userId, compensatableOrderAmount, orderStatus FROM OrderEntity WHERE id = '$order'") {
                listOf(it.getObject(1), it.getLong(2), it.getString(3))
            }).containsExactly(listOf(user, 7L, "PENDING"))
        }
    }
}
