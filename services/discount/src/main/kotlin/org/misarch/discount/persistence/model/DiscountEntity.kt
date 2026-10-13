package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.discount.event.model.DiscountDTO
import org.misarch.discount.graphql.model.Discount
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Entity for discounts
 * Discounts are applied relative to the original price: a discount of 0.2 is a 20% reduction in price.
 * Multiple discounts are applied multiplicatively:
 * discounted = original * (1 - discount1) * (1 - discount2) * ...
 *
 * @property discount the discount applied to the order item
 * @property maxUsagesPerUser the maximum number of times a user can use this discount in bought ProductItems
 * @property validUntil the date and time until which the discount is valid
 * @property validFrom the date and time from which the discount is valid
 * @property minOrderAmount the minimum order amount required to use this discount
 * @property id unique identifier of the discount
 */
@Table
class DiscountEntity(
    val discount: Double,
    var maxUsagesPerUser: Int?,
    var validUntil: OffsetDateTime,
    var validFrom: OffsetDateTime,
    var minOrderAmount: Int?,
    @Id
    override val id: UUID?
) : BaseEntity<Discount> {

    override fun toDTO(): Discount {
        return Discount(
            id = id!!,
            discount = discount,
            maxUsagesPerUser = maxUsagesPerUser,
            validUntil = validUntil,
            validFrom = validFrom,
            minOrderAmount = minOrderAmount
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @param discountAppliesToCategoryIds the category ids to which the discount applies
     * @param discountAppliesToProductIds the product ids to which the discount applies
     * @param discountAppliesToProductVariantIds the product variant ids to which the discount applies
     * @return the event DTO
     */
    fun toEventDTO(
        discountAppliesToCategoryIds: Set<UUID>,
        discountAppliesToProductIds: Set<UUID>,
        discountAppliesToProductVariantIds: Set<UUID>
    ): DiscountDTO {
        return DiscountDTO(
            id = id!!,
            discount = discount,
            maxUsagesPerUser = maxUsagesPerUser,
            validUntil = validUntil.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            validFrom = validFrom.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            minOrderAmount = minOrderAmount,
            discountAppliesToCategoryIds = discountAppliesToCategoryIds.toList(),
            discountAppliesToProductIds = discountAppliesToProductIds.toList(),
            discountAppliesToProductVariantIds = discountAppliesToProductVariantIds.toList()
        )
    }

}
