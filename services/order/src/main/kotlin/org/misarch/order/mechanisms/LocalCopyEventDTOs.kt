package org.misarch.order.mechanisms

import java.util.UUID

/**
 * DTO for a created or updated discount (`discount/discount/created|updated`, the fields of the local copy)
 *
 * @property id unique identifier of the discount
 * @property discount the discount (fraction of the price)
 * @property validFrom start of the validity (ISO date time)
 * @property validUntil end of the validity (ISO date time)
 * @property minOrderAmount the minimum order amount, if any
 * @property discountAppliesToProductVariantIds the product variants the discount applies to
 * @property discountAppliesToProductIds the products the discount applies to
 * @property discountAppliesToCategoryIds the categories the discount applies to
 */
data class LocalCopyDiscountDTO(
    val id: UUID,
    val discount: Double,
    val validFrom: String,
    val validUntil: String,
    val minOrderAmount: Long?,
    val discountAppliesToProductVariantIds: List<UUID> = emptyList(),
    val discountAppliesToProductIds: List<UUID> = emptyList(),
    val discountAppliesToCategoryIds: List<UUID> = emptyList()
)

/**
 * DTO for a created or updated product (`catalog/product/created|updated`, the fields of the local copy)
 *
 * @property id unique identifier of the product
 * @property categoryIds the ids of its categories
 */
data class LocalCopyProductDTO(
    val id: UUID,
    val categoryIds: List<UUID> = emptyList()
)

/**
 * DTO for a created product variant (`catalog/product-variant/created`, the fields of the local copy)
 *
 * @property id unique identifier of the product variant
 * @property productId the id of its product
 */
data class LocalCopyProductVariantDTO(
    val id: UUID,
    val productId: UUID
)

/**
 * DTO for an archived shipment method (`shipment/shipment-method/archived`)
 *
 * @property id unique identifier of the shipment method
 */
data class LocalCopyArchivedShipmentMethodDTO(
    val id: UUID
)
