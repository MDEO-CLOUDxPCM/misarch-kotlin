package org.misarch.order.persistence.model

import org.misarch.common.persistence.IdEntity
import org.misarch.common.persistence.requiredId
import org.misarch.order.event.model.OrderCompensationDTO
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for the compensation of order items whose shipment could not be created
 *
 * @property orderId the id of the order
 * @property triggeredAt the date and time the compensation was triggered
 * @property amountToCompensate the sum of the compensatable amounts of the items
 * @property id unique identifier of the compensation
 */
@Table
class OrderCompensationEntity(
    val orderId: UUID,
    val triggeredAt: OffsetDateTime,
    val amountToCompensate: Long,
    @Id
    override val id: UUID?
) : IdEntity {

    /**
     * Converts the entity to an event DTO
     *
     * @param orderItemIds the ids of the compensated order items
     * @return the event DTO
     */
    fun toEventDTO(orderItemIds: List<UUID>): OrderCompensationDTO {
        return OrderCompensationDTO(
            id = requiredId,
            orderId = orderId,
            orderItemIds = orderItemIds,
            triggeredAt = triggeredAt,
            amountToCompensate = amountToCompensate
        )
    }

}
