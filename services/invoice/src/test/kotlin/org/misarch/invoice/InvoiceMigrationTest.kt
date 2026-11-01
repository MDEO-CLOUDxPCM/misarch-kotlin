package org.misarch.invoice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V3 migration applied to rows written by the V2 schema.
 */
class InvoiceMigrationTest {

    @Test
    fun `invoices keep their address ids and the replicas their rows`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "2")
            val invoice = UUID.randomUUID()
            val order = UUID.randomUUID()
            val user = UUID.randomUUID()
            val userAddress = UUID.randomUUID()
            val vendorAddress = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO invoices (id, order_id, issued_at, content, user_address, vendor_address, vat_number)
                    VALUES ('$invoice', '$order', now(), 'c', jsonb_build_object('_id', '$userAddress', 'street1', 's'),
                    jsonb_build_object('_id', '$vendorAddress'), 'AB1234');
                INSERT INTO users (id, first_name, last_name) VALUES ('$user', 'Ada', 'Lovelace');
                INSERT INTO user_address (id, user_id, street1, street2, city, postal_code, country, archived)
                    VALUES ('$userAddress', '$user', 's1', 's2', 'c', 'p', 'DE', true);
                INSERT INTO vendor_address (id, postal_code) VALUES ('$vendorAddress', 'p');
                """.trimIndent()
            )
            db.migrate()

            assertThat(db.query("SELECT orderId, userAddressId, vendorAddressId, vatNumber FROM InvoiceEntity WHERE id = '$invoice'") {
                listOf(it.getObject(1), it.getObject(2), it.getObject(3), it.getString(4))
            }).containsExactly(listOf(order, userAddress, vendorAddress, "AB1234"))
            assertThat(db.query("SELECT firstName, lastName FROM UserEntity WHERE id = '$user'") {
                it.getString(1) + " " + it.getString(2)
            }).containsExactly("Ada Lovelace")
            assertThat(db.query("SELECT userId, postalCode, isArchived FROM UserAddressEntity WHERE id = '$userAddress'") {
                listOf(it.getObject(1), it.getString(2), it.getBoolean(3))
            }).containsExactly(listOf(user, "p", true))
            assertThat(db.query("SELECT postalCode, seq FROM VendorAddressEntity WHERE id = '$vendorAddress'") {
                it.getString(1)
            }).containsExactly("p")
        }
    }
}
