package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.model.CategoryCharacteristicValueEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [CategoryCharacteristicValueEntity]s
 */
interface CategoryCharacteristicValueRepository : CoroutineCrudRepository<CategoryCharacteristicValueEntity, UUID> {

    /**
     * Upserts [CategoryCharacteristicValueEntity] with given [categoryCharacteristicId] and [productVariantVersionId]
     * If [CategoryCharacteristicValueEntity] with given [categoryCharacteristicId] and [productVariantVersionId] already exists, it will be updated
     * Otherwise, new [CategoryCharacteristicValueEntity] will be created
     *
     * @param categoryCharacteristicId id of the referenced characteristic
     * @param productVariantVersionId id of the referenced product variant version owning the value
     * @param stringValue [String] value to upsert
     * @param doubleValue [Double] value to upsert
     * @param discriminator name of the [org.misarch.catalog.persistence.model.CategoryCharacteristicDiscriminator]
     */
    @Modifying
    @Query(
        """
            INSERT INTO CategoryCharacteristicValueEntity (categoryCharacteristicId, productVariantVersionId, stringValue, doubleValue, discriminator)
            VALUES (:categoryCharacteristicId, :productVariantVersionId, :stringValue, :doubleValue, :discriminator)
            ON CONFLICT (categoryCharacteristicId, productVariantVersionId)
            DO UPDATE SET stringValue = :stringValue, doubleValue = :doubleValue
        """
    )
    suspend fun upsert(
        categoryCharacteristicId: UUID,
        productVariantVersionId: UUID,
        stringValue: String?,
        doubleValue: Double?,
        discriminator: String
    )

    /**
     * Takes a list of category characteristic ids and a product variant version id
     * and returns only the [CategoryCharacteristicEntity]s which are compatible with the product variant version,
     * meaning that the associated product has the associated characteristic.
     * MUST NOT USE AN EMPTY LIST FOR [categoryCharacteristicIds]!
     *
     * @param productVariantVersionId the id of the product variant
     * @param categoryCharacteristicIds the ids of the category characteristics
     * @return the valid [CategoryCharacteristicEntity]s
     */
    @Query(
        """
            SELECT DISTINCT categoryCharacteristic.*
            FROM ProductVariantVersionEntity productVariantVersion
            JOIN ProductVariantEntity productVariant ON productVariantVersion.productVariantId = productVariant.id
            JOIN ProductEntity product ON productVariant.productId = product.id
            JOIN ProductToCategoryEntity productToCategory ON product.id = productToCategory.productId
            JOIN CategoryEntity category ON productToCategory.categoryId = category.id
            JOIN CategoryCharacteristicEntity categoryCharacteristic ON category.id = categoryCharacteristic.categoryId
            WHERE productVariantVersion.id = :productVariantVersionId
              AND categoryCharacteristic.id IN (:categoryCharacteristicIds);
        """
    )
    suspend fun findValidCategoryCharacteristics(
        productVariantVersionId: UUID,
        categoryCharacteristicIds: List<UUID>
    ): List<CategoryCharacteristicEntity>

}
