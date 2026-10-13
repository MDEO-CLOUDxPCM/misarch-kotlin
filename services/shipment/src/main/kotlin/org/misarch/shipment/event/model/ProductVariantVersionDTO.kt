package org.misarch.shipment.event.model

import java.util.*

/**
 * ProductVariantVersion DTO used for events
 *
 * @property id id of the product variant version
 * @property weight weight of the product variant version
 */
data class ProductVariantVersionDTO(
    val id: UUID,
    val weight: Double
)