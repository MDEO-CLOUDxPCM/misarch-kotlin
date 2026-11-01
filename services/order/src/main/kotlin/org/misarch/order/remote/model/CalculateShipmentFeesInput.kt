package org.misarch.order.remote.model

import java.util.UUID

/**
 * Input of shipment's `calculateShipmentFees`
 *
 * @property items the items of the order
 */
data class CalculateShipmentFeesInput(
    val items: List<ProductVariantVersionWithQuantityAndShipmentMethodInput>
)

/**
 * An item in [CalculateShipmentFeesInput]
 *
 * @property productVariantVersionId the id of the product variant version
 * @property quantity the quantity
 * @property shipmentMethodId the id of the shipment method
 */
data class ProductVariantVersionWithQuantityAndShipmentMethodInput(
    val productVariantVersionId: UUID,
    val quantity: Long,
    val shipmentMethodId: UUID
)
