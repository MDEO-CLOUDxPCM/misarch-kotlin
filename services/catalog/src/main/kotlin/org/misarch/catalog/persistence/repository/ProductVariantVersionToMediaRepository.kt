package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductVariantVersionToMediaEntity
import org.misarch.catalog.persistence.model.ProductVariantVersionToMediaKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Repository for [ProductVariantVersionToMediaEntity]s
 */
interface ProductVariantVersionToMediaRepository :
    CoroutineCrudRepository<ProductVariantVersionToMediaEntity, ProductVariantVersionToMediaKey>
