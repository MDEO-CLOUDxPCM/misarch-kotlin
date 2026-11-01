package org.misarch.catalog.persistence.model

import org.misarch.catalog.event.model.CreatedProductVariantDTO
import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variants
 *
 * @property isPubliclyVisible whether the product variant is publicly visible
 * @property productId id of the referenced product
 * @property currentVersion id of the current version of the product variant
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    var isPubliclyVisible: Boolean,
    val productId: UUID,
    var currentVersion: UUID?,
    @Id
    override val id: UUID?
) : BaseEntity<ProductVariant> {

    override fun toDTO(): ProductVariant {
        return ProductVariant(
            id = id!!,
            isPubliclyVisible = isPubliclyVisible,
            productId = productId,
            currentVersion = currentVersion!!
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return event DTO
     */
    fun toEventDTO(): CreatedProductVariantDTO {
        return CreatedProductVariantDTO(
            id = id!!,
            productId = productId,
            currentVersionId = currentVersion!!,
            isPubliclyVisible = isPubliclyVisible
        )
    }

}