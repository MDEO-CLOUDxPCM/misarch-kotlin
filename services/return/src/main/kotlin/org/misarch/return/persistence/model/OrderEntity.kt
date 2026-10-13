package org.misarch.`return`.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for an order (replica, fed by `order/order/created`)
 *
 * @property userId id of the user the order is connected to
 * @property id unique identifier of the order
 */
@Table
class OrderEntity(
    val userId: UUID,
    @Id
    val id: UUID
)
