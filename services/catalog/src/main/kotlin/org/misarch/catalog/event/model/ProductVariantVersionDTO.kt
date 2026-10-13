package org.misarch.catalog.event.model

import java.util.UUID

/**
 * Product variant version DTO used for events
 *
 * @property id unique identifier of the product variant version
 * @property name name of the product variant version
 * @property description description of the product variant version
 * @property version version of the product variant version
 * @property retailPrice retail price of the product variant version
 * @property createdAt creation timestamp of the product variant version
 * @property canBeReturnedForDays number of days the product variant version can be returned, null if it can be returned indefinitely
 * @property productVariantId id of the referenced product variant
 * @property taxRateId id of the referenced tax rate
 * @property weight weight of the product variant version
 * @property mediaIds list of media ids associated with the product variant version
 */
data class ProductVariantVersionDTO(
    val id: UUID,
    val name: String,
    val description: String,
    val version: Int,
    val retailPrice: Int,
    val createdAt: String,
    val canBeReturnedForDays: Int?,
    val productVariantId: UUID,
    val taxRateId: UUID,
    val weight: Double,
    val mediaIds: List<UUID>
)