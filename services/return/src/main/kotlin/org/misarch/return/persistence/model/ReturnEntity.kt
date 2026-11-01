package org.misarch.`return`.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.`return`.event.model.ReturnDTO
import org.misarch.`return`.graphql.model.Return
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for returns
 *
 * @property reason the reason for the return
 * @property orderId the id of the order the return is associated with
 * @property refundedAmount the amount of money refunded
 * @property createdAt the time the return was created
 * @property id unique identifier of the return
 */
@Table
class ReturnEntity(
    val reason: String,
    val orderId: UUID,
    val refundedAmount: Long,
    val createdAt: OffsetDateTime,
    @Id
    override val id: UUID?
) : BaseEntity<Return> {

    override fun toDTO(): Return {
        return Return(
            id = requiredId,
            reason = reason,
            orderId = orderId,
            // the schema has a GraphQL Int: an amount beyond its range fails instead of wrapping
            refundedAmount = Math.toIntExact(refundedAmount),
            createdAt = createdAt
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @param orderItemIds the ids of the order items returned
     * @return event DTO
     */
    fun toEventDTO(orderItemIds: List<UUID>): ReturnDTO {
        return ReturnDTO(
            id = requiredId,
            orderId = orderId,
            orderItemIds = orderItemIds,
            reason = reason,
            refundedAmount = refundedAmount,
            createdAt = createdAt
        )
    }

}
