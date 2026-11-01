package org.misarch.order.mechanisms

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Local copy of discount's discount ([LocalCopies]): validity, minimum order amount and what it applies to
 *
 * @property id unique identifier of the discount
 * @property discount the discount (fraction of the price)
 * @property validFrom start of the validity
 * @property validUntil end of the validity
 * @property minOrderAmount the minimum order amount, if any
 * @property appliesVariantIds the product variants the discount applies to
 * @property appliesProductIds the products the discount applies to
 * @property appliesCategoryIds the categories the discount applies to
 */
@Table
class LocalCopyDiscountEntity(
    @Id
    val id: UUID,
    val discount: Double,
    val validFrom: OffsetDateTime,
    val validUntil: OffsetDateTime,
    val minOrderAmount: Long?,
    val appliesVariantIds: List<UUID>,
    val appliesProductIds: List<UUID>,
    val appliesCategoryIds: List<UUID>
)

/**
 * Local copy of shipment's shipment method ([LocalCopies]): its fees and whether it is archived
 *
 * @property id unique identifier of the shipment method
 * @property baseFees the base fees
 * @property feesPerItem the fees per item
 * @property feesPerKg the fees per kg
 * @property archived whether the shipment method is archived
 */
@Table
class LocalCopyShipmentMethodEntity(
    @Id
    val id: UUID,
    val baseFees: Long,
    val feesPerItem: Long,
    val feesPerKg: Long,
    val archived: Boolean
)

/**
 * Local copy of the weight of catalog's product variant version ([LocalCopies])
 *
 * @property id unique identifier of the product variant version
 * @property weight the weight of the version
 */
@Table
class LocalCopyProductVariantVersionEntity(
    @Id
    val id: UUID,
    val weight: Double
)

/**
 * Local copy of the product of catalog's product variant ([LocalCopies])
 *
 * @property id unique identifier of the product variant
 * @property productId the id of its product
 */
@Table
class LocalCopyProductVariantEntity(
    @Id
    val id: UUID,
    val productId: UUID
)

/**
 * Local copy of the categories of catalog's product ([LocalCopies])
 *
 * @property id unique identifier of the product
 * @property categoryIds the ids of its categories
 */
@Table
class LocalCopyProductEntity(
    @Id
    val id: UUID,
    val categoryIds: List<UUID>
)

/**
 * Local copy of the discount of discount's coupon ([LocalCopies]); a discount with a coupon never applies locally
 *
 * @property id unique identifier of the coupon
 * @property discountId the id of its discount
 */
@Table
class LocalCopyCouponEntity(
    @Id
    val id: UUID,
    val discountId: UUID
)

/**
 * A product variant of the local copies with its product and the product's categories (null if the product is not
 * in the copy)
 *
 * @property id unique identifier of the product variant
 * @property productId the id of its product
 * @property categoryIds the ids of the product's categories
 */
data class LocalCopyProductVariantCategories(
    val id: UUID,
    val productId: UUID,
    val categoryIds: List<UUID>?
)
