package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class OrderSchemaTest {

    @Test
    fun `the database generates the ids of orders, their items and compensations`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val order = db.query(
                "INSERT INTO OrderEntity (userId, createdAt, orderStatus, shipmentAddressId, invoiceAddressId, " +
                    "compensatableOrderAmount, paymentInformationId) VALUES ('${UUID.randomUUID()}', now(), 'PENDING', " +
                    "'${UUID.randomUUID()}', '${UUID.randomUUID()}', 7, '${UUID.randomUUID()}') RETURNING id"
            ) { it.getObject(1) }.single()
            val compensation = db.query(
                "INSERT INTO OrderCompensationEntity (orderId, triggeredAt, amountToCompensate) " +
                    "VALUES ('$order', now(), 5) RETURNING id"
            ) { it.getObject(1) }.single()

            assertThat(order).isInstanceOf(UUID::class.java)
            assertThat(compensation).isInstanceOf(UUID::class.java)
        }
    }

    @Test
    fun `an item belongs to a known order and an address to a known user`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            assertThatThrownBy {
                db.execute("INSERT INTO UserAddressEntity (userId, id) VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}')")
            }.hasMessageContaining("useraddressentity_userid_fkey")
            assertThatThrownBy {
                db.execute(
                    "INSERT INTO OrderItemToDiscountEntity (orderItemId, discountId, discount) " +
                        "VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 0.1)"
                )
            }.hasMessageContaining("orderitemtodiscountentity_orderitemid_fkey")
        }
    }
}
