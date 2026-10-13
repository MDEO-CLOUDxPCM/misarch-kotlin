package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.discount.graphql.model.ProductVariant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for product variants
 *
 * @property productId unique identifier of the product variant
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    val productId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<ProductVariant> {

    override fun toDTO(): ProductVariant {
        return ProductVariant(
            id = id!!,
        )
    }
}
