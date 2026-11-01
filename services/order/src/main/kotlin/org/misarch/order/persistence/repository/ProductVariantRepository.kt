package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductVariantEntity]s
 */
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /**
     * Sets the current version of a product variant; an unknown product variant is created as publicly visible,
     * the visibility of a known one is kept
     *
     * @param id the id of the product variant
     * @param versionId the id of the version
     * @param price the retail price of the version
     * @param taxRateId the id of the tax rate of the version
     */
    @Modifying
    @Query(
        "INSERT INTO ProductVariantEntity (id, currentVersionId, currentVersionPrice, currentVersionTaxRateId, isPubliclyVisible) " +
            "VALUES (:id, :versionId, :price, :taxRateId, TRUE) ON CONFLICT (id) DO UPDATE SET " +
            "currentVersionId = EXCLUDED.currentVersionId, currentVersionPrice = EXCLUDED.currentVersionPrice, " +
            "currentVersionTaxRateId = EXCLUDED.currentVersionTaxRateId"
    )
    suspend fun upsertCurrentVersion(id: UUID, versionId: UUID, price: Long, taxRateId: UUID)

    /**
     * Sets the visibility of a known product variant
     *
     * @param id the id of the product variant
     * @param isPubliclyVisible whether it is publicly visible
     */
    @Modifying
    @Query("UPDATE ProductVariantEntity SET isPubliclyVisible = :isPubliclyVisible WHERE id = :id")
    suspend fun updateVisibility(id: UUID, isPubliclyVisible: Boolean)

}
