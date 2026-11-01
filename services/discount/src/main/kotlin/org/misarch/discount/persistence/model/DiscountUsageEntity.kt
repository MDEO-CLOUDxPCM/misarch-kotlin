package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.discount.graphql.model.DiscountUsage
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between discounts and users
 * Also contains the usage count of the discount by the user
 *
 * @property discountId id of the discount
 * @property userId id of the user
 * @property usages the number of times the user has used the discount
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class DiscountUsageEntity(
    val discountId: UUID,
    val userId: UUID,
    val usages: Long,
    @Id
    override val id: UUID?
) : BaseEntity<DiscountUsage> {

    override fun toDTO(): DiscountUsage {
        return DiscountUsage(
            id = requiredId,
            discountId = discountId,
            userId = userId,
            usages = usages.toInt()
        )
    }
}
