package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.OrderCompensationEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OrderCompensationEntity]s
 */
interface OrderCompensationRepository : CoroutineCrudRepository<OrderCompensationEntity, UUID>
