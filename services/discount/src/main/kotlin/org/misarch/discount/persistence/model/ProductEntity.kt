package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.discount.graphql.model.Product
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for products
 *
 * @property id unique identifier of the product
 */
@Table
class ProductEntity(
    @Id
    override val id: UUID?
) : BaseEntity<Product> {

    override fun toDTO(): Product {
        return Product(
            id = id!!,
        )
    }
}
