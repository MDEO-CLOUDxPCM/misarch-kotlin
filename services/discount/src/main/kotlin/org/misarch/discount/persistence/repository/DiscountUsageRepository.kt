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
     * NOTE (faithful to the original, defect Q1 in docs/specs/discount.md): the statement uses the snake_case columns
     * `discount_id`/`user_id`, which the Flyway schema does not have (it has `discountId`/`userId`). On a stock schema
     * it therefore always fails; the calibration setup adds the columns (`workarounds/discount_usage_columns.sql`).
     * The SQL text is kept identical so that the port behaves the same with and without that workaround.
     *
     * @param discountId id of the discount
     * @param userId id of the user
     * @param usages the number of times the user has used the discount
     */
    @Modifying
    @Query(
        """
            INSERT INTO DiscountUsageEntity (discount_id, user_id, usages)
            VALUES (:discountId, :userId, :usages)
            ON CONFLICT (discount_id, user_id)
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
