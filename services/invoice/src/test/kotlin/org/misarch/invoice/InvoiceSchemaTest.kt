package org.misarch.invoice

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults and constraints of the schema, on a freshly migrated database.
 */
class InvoiceSchemaTest {

    @Test
    fun `an order has one invoice`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val order = UUID.randomUUID()
            val insert = {
                "INSERT INTO InvoiceEntity (id, orderId, issuedAt, content, userAddressId, vendorAddressId) " +
                    "VALUES ('${UUID.randomUUID()}', '$order', now(), 'c', '${UUID.randomUUID()}', '${UUID.randomUUID()}')"
            }
            db.execute(insert())
            assertThatThrownBy { db.execute(insert()) }.hasMessageContaining("invoiceentity_orderid_key")
        }
    }

    @Test
    fun `vendor addresses get ascending versions and need all address lines`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            for (id in listOf(first, second)) {
                db.execute(
                    "INSERT INTO VendorAddressEntity (id, street1, street2, city, postalCode, country) " +
                        "VALUES ('$id', 's', '2', 'c', 'p', 'DE')"
                )
            }

            assertThat(db.query("SELECT id FROM VendorAddressEntity ORDER BY version") { it.getObject(1) })
                .containsExactly(first, second)
            assertThatThrownBy { db.execute("INSERT INTO VendorAddressEntity (id) VALUES ('${UUID.randomUUID()}')") }
                .hasMessageContaining("street1")
        }
    }
}
