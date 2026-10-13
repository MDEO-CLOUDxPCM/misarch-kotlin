package org.misarch.shipment.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product variant versions
 *
 * @property weight weight of the product variant version in kg
 * @property id unique identifier of the product variant version
 */
@Table
class ProductVariantVersionEntity(
    val weight: Double,
    @Id
    val id: UUID
)
