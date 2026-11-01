package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.ProductVariantVersionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductVariantVersionEntity]s
 */
interface ProductVariantVersionRepository : CoroutineCrudRepository<ProductVariantVersionEntity, UUID> {

    /**
     * Inserts the product variant version replica; idempotent (a redelivery overwrites with the same value)
     */
    @Modifying
    @Query(
        """
        INSERT INTO ProductVariantVersionEntity (id, canBeReturnedForDays) VALUES (:id, :canBeReturnedForDays)
        ON CONFLICT (id) DO UPDATE SET canBeReturnedForDays = EXCLUDED.canBeReturnedForDays
        """
    )
    suspend fun createProductVariantVersion(
        id: UUID,
        canBeReturnedForDays: Int?
    )

}
