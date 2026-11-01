package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for a discount applied to an order item
 *
 * @property key the order item and the discount
 * @property discount the discount (fraction of the price, `0.1` = 10 %)
 */
@Table
class OrderItemToDiscountEntity(
    @Id
    val key: OrderItemToDiscountKey,
    val discount: Double
) : Persistable<OrderItemToDiscountKey> {

    override fun getId(): OrderItemToDiscountKey = key

    /** Rows are only inserted, never updated. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [OrderItemToDiscountEntity].
 *
 * @property orderItemId the id of the order item
 * @property discountId the id of the discount
 */
data class OrderItemToDiscountKey(val orderItemId: UUID, val discountId: UUID)
