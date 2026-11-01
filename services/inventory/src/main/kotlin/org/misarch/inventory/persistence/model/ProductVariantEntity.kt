package org.misarch.inventory.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variants (replicated from the catalog, only the id)
 *
 * @property id unique identifier of the product variant
 */
@Table
class ProductVariantEntity(
    @Id
    val id: UUID
)
