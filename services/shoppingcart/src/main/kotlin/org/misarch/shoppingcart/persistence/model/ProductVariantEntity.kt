package org.misarch.shoppingcart.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shoppingcart.graphql.model.ProductVariant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variants (replica of catalog's product variants: only the id, fed by
 * `catalog/product-variant/created`)
 *
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    @Id
    override val id: UUID
) : BaseEntity<ProductVariant> {

    override fun toDTO(): ProductVariant {
        return ProductVariant(
            id = id
        )
    }

}
