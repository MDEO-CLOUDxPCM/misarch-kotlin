package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class PaymentSchemaTest {

    @Test
    fun `the database generates ids and counts no retries`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val info = db.query(
                "INSERT INTO PaymentInformationEntity (paymentMethod, userId) VALUES ('INVOICE', '${UUID.randomUUID()}') RETURNING id"
            ) { it.getObject(1) }.single()
            db.execute(
                "INSERT INTO PaymentEntity (totalAmount, status, paymentInformationId, createdAt, updatedAt) " +
                    "VALUES (1999, 'PENDING', '$info', now(), now())"
            )

            assertThat(db.query("SELECT numberOfRetries FROM PaymentEntity") { it.getInt(1) }).containsExactly(0)
        }
    }

    @Test
    fun `an order has one open order`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val order = UUID.randomUUID()
            val insert = { "INSERT INTO OpenOrderEntity (paymentId, orderId, orderData) VALUES ('${UUID.randomUUID()}', '$order', '{}')" }
            db.execute(insert())
            assertThatThrownBy { db.execute(insert()) }.hasMessageContaining("openorderentity_orderid_key")
        }
    }
}
