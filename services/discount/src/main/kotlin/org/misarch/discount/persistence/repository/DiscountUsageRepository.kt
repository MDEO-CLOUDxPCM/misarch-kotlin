package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.DiscountUsageEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [DiscountUsageEntity]s
 */
@Repository
interface DiscountUsageRepository : CoroutineCrudRepository<DiscountUsageEntity, UUID> {

    /**
     * Upsert a discount usage
     *
     * FIXED (docs/fixes/discount.md, Q1): the original used the snake_case columns `discount_id`/`user_id`, which the
     * Flyway schema does not have, so every order with a discount failed validation. The statement now uses the real
     * columns `discountId`/`userId` and their unique constraint; it also works on a database that has the calibration
     * workaround (`workarounds/discount_usage_columns.sql`) applied (its sync trigger fills the extra columns).
     *
     * @param discountId id of the discount
     * @param userId id of the user
     * @param usages the number of times the user has used the discount
     */
    @Modifying
    @Query(
        """
            INSERT INTO DiscountUsageEntity (discountId, userId, usages)
            VALUES (:discountId, :userId, :usages)
            ON CONFLICT (discountId, userId)
            DO UPDATE SET usages = DiscountUsageEntity.usages + :usages
        """
    )
    suspend fun upsertDiscountUsage(discountId: UUID, userId: UUID, usages: Long)

    /**
     * Find discount usages by user id and  a collection of discount ids
     *
     * @param userId id of the user
     * @param discountIds ids of the discounts
     * @return the discount usage, null if not found
     */
    suspend fun findByUserIdAndDiscountIdIn(userId: UUID, discountIds: Collection<UUID>): List<DiscountUsageEntity>

}
