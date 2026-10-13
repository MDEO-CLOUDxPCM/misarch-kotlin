package org.misarch.`return`.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for a product variant version (replica; never fed, see EventController.onProductVariantVersionCreated)
 *
 * @property canBeReturnedForDays number of days the productVariantVersion can be returned, null if it can be returned forever
 * @property id unique identifier of the productVariantVersion item
 */
@Table
class ProductVariantVersionEntity(
    val canBeReturnedForDays: Int?,
    @Id
    val id: UUID
)
