package org.misarch.shipment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * Defaults, constraints and triggers of the schema, on a freshly migrated database.
 */
class ShipmentSchemaTest {

    @Test
    fun `addresses get ascending versions, a shipment lists an order item once`() {
        MigrationTestDatabase().use { db ->
            db.migrate()
            val versions = List(2) {
                db.query(
                    "INSERT INTO AddressEntity (id, street1, street2, city, postalCode, country) " +
                        "VALUES ('${UUID.randomUUID()}', 's', 's', 'c', 'p', 'DE') RETURNING version"
                ) { it.getLong(1) }.single()
            }
            assertThat(versions[1]).isGreaterThan(versions[0])
            val address = db.query("SELECT id FROM AddressEntity LIMIT 1") { it.getObject(1) }.single()
            val shipment = db.query(
                """
                WITH m AS (INSERT INTO ShipmentMethodEntity (name, description, externalReference, baseFees, feesPerItem, feesPerKg)
                    VALUES ('m', 'd', 'r', 1, 1, 1) RETURNING id)
                INSERT INTO ShipmentEntity (status, shipmentMethodId, shipmentAddressId) SELECT 'PENDING', id, '$address' FROM m RETURNING id
                """.trimIndent()
            ) { it.getObject(1) }.single()
            val item = UUID.randomUUID()
            db.execute("INSERT INTO OrderItemEntity (id, sentWithId, quantity, productVariantVersionId) VALUES ('$item', '$shipment', 1, gen_random_uuid())")
            val link = "INSERT INTO ShipmentToOrderItemEntity (shipmentId, orderItemId) VALUES ('$shipment', '$item')"
            db.execute(link)
            assertThatThrownBy { db.execute(link) }.hasMessageContaining("shipmenttoorderitementity_pkey")
        }
    }
}
