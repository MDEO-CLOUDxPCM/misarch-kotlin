package org.misarch.review.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.review.graphql.model.ProductVariant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variants (replica of catalog's product variants, fed by `catalog/product-variant/created`)
 *
 * @property productId id of the product the variant belongs to
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    val productId: UUID,
    @Id
    override val id: UUID
) : BaseEntity<ProductVariant> {

    override fun toDTO(): ProductVariant {
        return ProductVariant(
            id = id
        )
    }

}
