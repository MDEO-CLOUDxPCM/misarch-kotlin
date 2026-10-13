package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.ProductVariantVersionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ProductVariantVersionEntity]s
 */
@Repository
interface ProductVariantVersionRepository : CoroutineCrudRepository<ProductVariantVersionEntity, UUID> {

    @Modifying
    @Query("INSERT INTO ProductVariantVersionEntity (id, canBeReturnedForDays) VALUES (:id, :canBeReturnedForDays)")
    suspend fun createProductVariantVersion(
        @Param("id") id: UUID,
        @Param("canBeReturnedForDays") canBeReturnedForDays: Int?
    )

}
