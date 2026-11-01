package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created product variant version (the fields order uses)
 *
 * @property id unique identifier of the product variant version
 * @property retailPrice the retail price of the version
 * @property taxRateId the id of the tax rate of the version
 * @property productVariantId the id of the product variant
 * @property weight the weight of the version (only read by the local copies, docs/MECHANISMS.md)
 */
data class ProductVariantVersionDTO(
    val id: UUID,
    val retailPrice: Long,
    val taxRateId: UUID,
    val productVariantId: UUID,
    val weight: Double? = null
)
