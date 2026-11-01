package org.misarch.inventory.event.model

import java.util.UUID

/**
 * Product variant created in the catalog (only the id is used)
 *
 * @property id the id of the product variant
 */
data class ProductVariantDTO(val id: UUID)
