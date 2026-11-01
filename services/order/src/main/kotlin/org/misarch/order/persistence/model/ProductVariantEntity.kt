package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variants with their current version (replica, fed by `catalog/product-variant-version/created`
 * and `catalog/product-variant/updated`)
 *
 * @property currentVersionId the id of the current product variant version
 * @property currentVersionPrice the retail price of the current version
 * @property currentVersionTaxRateId the id of the tax rate of the current version
 * @property isPubliclyVisible whether the product variant is publicly visible (only those can be ordered)
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    val currentVersionId: UUID,
    val currentVersionPrice: Long,
    val currentVersionTaxRateId: UUID,
    val isPubliclyVisible: Boolean,
    @Id
    val id: UUID
)
