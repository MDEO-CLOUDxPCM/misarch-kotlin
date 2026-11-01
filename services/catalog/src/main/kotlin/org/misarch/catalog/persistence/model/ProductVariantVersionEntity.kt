package org.misarch.catalog.persistence.model

import org.misarch.catalog.event.model.ProductVariantVersionDTO
import org.misarch.catalog.graphql.model.ProductVariantVersion
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.*

/**
 * Entity for product variant versions
 *
 * @property name name of the product variant version
 * @property description description of the product variant version
 * @property version version of the product variant version
 * @property retailPrice retail price of the product variant version
 * @property createdAt creation timestamp of the product variant version
 * @property canBeReturnedForDays number of days the product variant version can be returned, null if it can be returned indefinitely
 * @property productVariantId id of the referenced product variant
 * @property id unique identifier of the product variant version
 */
@Table
class ProductVariantVersionEntity(
    val name: String,
    val description: String,
    val version: Int,
    val retailPrice: Int,
    val createdAt: OffsetDateTime,
    val canBeReturnedForDays: Int?,
    val productVariantId: UUID,
    val taxRateId: UUID,
    val weight: Double,
    @Id
    override val id: UUID?
) : BaseEntity<ProductVariantVersion> {

    override fun toDTO(): ProductVariantVersion {
        return ProductVariantVersion(
            id = id!!,
            name = name,
            description = description,
            version = version,
            retailPrice = retailPrice,
            createdAt = createdAt,
            canBeReturnedForDays = canBeReturnedForDays,
            productVariantId = productVariantId,
            taxRateId = taxRateId,
            weight = weight
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @param mediaIds list of media ids associated with the product variant version
     * @return event DTO
     */
    fun toEventDTO(mediaIds: Set<UUID>): ProductVariantVersionDTO {
        return ProductVariantVersionDTO(
            id = id!!,
            name = name,
            description = description,
            version = version,
            retailPrice = retailPrice,
            createdAt = createdAt,
            canBeReturnedForDays = canBeReturnedForDays,
            productVariantId = productVariantId,
            taxRateId = taxRateId,
            weight = weight,
            mediaIds = mediaIds.toList()
        )
    }

}