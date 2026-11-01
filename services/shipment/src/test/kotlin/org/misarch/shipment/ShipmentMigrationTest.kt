package org.misarch.shipment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MigrationTestDatabase
import java.util.UUID

/**
 * The V2 migration applied to rows written by the V1 schema.
 */
class ShipmentMigrationTest {

    @Test
    fun `link table is deduplicated and gets the pair as primary key`() {
        MigrationTestDatabase().use { db ->
            db.migrate(target = "1.7")
            val address = UUID.randomUUID()
            val method = UUID.randomUUID()
            val shipment = UUID.randomUUID()
            val item1 = UUID.randomUUID()
            val item2 = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO AddressEntity (id, street1, street2, city, postalCode, country) VALUES ('$address', 's', 's', 'c', 'p', 'c');
                INSERT INTO ShipmentMethodEntity (id, name, description, externalReference, baseFees, feesPerItem, feesPerKg)
                    VALUES ('$method', 'm', 'd', 'r', 1, 1, 1);
                INSERT INTO ShipmentEntity (id, status, shipmentMethodId, shipmentAddressId) VALUES ('$shipment', 'PENDING', '$method', '$address');
                INSERT INTO OrderItemEntity (id, sentWithId, quantity, productVariantVersionId) VALUES ('$item1', '$shipment', 1, gen_random_uuid());
                INSERT INTO OrderItemEntity (id, sentWithId, quantity, productVariantVersionId) VALUES ('$item2', '$shipment', 1, gen_random_uuid());
                INSERT INTO ShipmentToOrderItemEntity (shipmentId, orderItemId) VALUES ('$shipment', '$item1');
                INSERT INTO ShipmentToOrderItemEntity (shipmentId, orderItemId) VALUES ('$shipment', '$item1');
                INSERT INTO ShipmentToOrderItemEntity (shipmentId, orderItemId) VALUES ('$shipment', '$item2');
                """.trimIndent()
            )
            db.migrate()

            assertThat(db.query("SELECT orderItemId FROM ShipmentToOrderItemEntity ORDER BY orderItemId") {
                it.getObject(1) as UUID
            }).containsExactlyElementsOf(listOf(item1, item2).sortedBy { it.toString() }) // Postgres orders UUIDs bytewise
            assertThat(db.query(
                """
                SELECT a.attname FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                WHERE i.indrelid = 'shipmenttoorderitementity'::regclass AND i.indisprimary
                ORDER BY array_position(i.indkey::int2[], a.attnum)
                """.trimIndent()
            ) { it.getString(1) }).containsExactly("shipmentid", "orderitemid")
            assertThat(db.query("SELECT indexname FROM pg_indexes WHERE tablename = 'shipmenttoorderitementity'") {
                it.getString(1)
            }).containsExactly("shipmenttoorderitementity_pkey")
        }
    }
}
