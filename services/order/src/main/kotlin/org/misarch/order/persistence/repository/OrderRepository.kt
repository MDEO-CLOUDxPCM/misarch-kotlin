package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.OrderEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [OrderEntity]s
 */
interface OrderRepository : CoroutineCrudRepository<OrderEntity, UUID> {

    /**
     * Places the order if it is still pending (compare-and-set: of concurrent placements only one succeeds)
     *
     * @param id the id of the order
     * @param placedAt the date and time of the placement
     * @return the number of placed orders (0 or 1)
     */
    @Modifying
    @Query("UPDATE OrderEntity SET orderStatus = 'PLACED', placedAt = :placedAt WHERE id = :id AND orderStatus = 'PENDING'")
    suspend fun placeIfPending(id: UUID, placedAt: OffsetDateTime): Int

    /**
     * Rejects the order if it is still pending (compare-and-set, see [placeIfPending])
     *
     * @param id the id of the order
     * @return the number of rejected orders (0 or 1)
     */
    @Modifying
    @Query("UPDATE OrderEntity SET orderStatus = 'REJECTED' WHERE id = :id AND orderStatus = 'PENDING'")
    suspend fun rejectIfPending(id: UUID): Int

}
