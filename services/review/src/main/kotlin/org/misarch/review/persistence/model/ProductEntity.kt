package org.misarch.review.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.review.graphql.model.Product
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for products (replica of catalog's products: only the id, fed by `catalog/product/created`)
 *
 * @property id unique identifier of the product
 */
@Table
class ProductEntity(
    @Id
    override val id: UUID
) : BaseEntity<Product> {

    override fun toDTO(): Product {
        return Product(
            id = id
        )
    }

}
