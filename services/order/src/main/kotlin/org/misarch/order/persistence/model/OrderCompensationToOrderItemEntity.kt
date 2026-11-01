package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Id of an [OrderCompensationToOrderItemEntity]
 *
 * @property orderCompensationId the id of the compensation
 * @property orderItemId the id of the compensated order item
 */
data class OrderCompensationToOrderItemId(
    val orderCompensationId: UUID,
    val orderItemId: UUID
)

/**
 * Entity for an order item compensated by an order compensation
 *
 * @property key the compensation and the order item
 * @property position the position of the item in the compensation
 */
@Table
class OrderCompensationToOrderItemEntity(
    @Id
    val key: OrderCompensationToOrderItemId,
    val position: Int
) : Persistable<OrderCompensationToOrderItemId> {

    override fun getId() = key

    /** Rows are only inserted, never updated. */
    override fun isNew() = true

}
