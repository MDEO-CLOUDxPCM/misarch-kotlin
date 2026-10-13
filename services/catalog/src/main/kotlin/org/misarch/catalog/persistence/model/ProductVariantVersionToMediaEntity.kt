package org.misarch.catalog.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between product variant versions and medias
 *
 * @property productVariantVersionId id of the product
 * @property mediaId id of the category
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class ProductVariantVersionToMediaEntity(
    val productVariantVersionId: UUID,
    val mediaId: UUID,
    @Id
    val id: UUID?
) {

}