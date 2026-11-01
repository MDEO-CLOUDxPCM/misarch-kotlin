package org.misarch.review.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.review.graphql.model.ProductVariant
import org.misarch.review.persistence.model.ProductVariantEntity
import org.misarch.review.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [ProductVariant]s (replicated ids only)
 */
@Component
class ProductVariantDataLoader(
    repository: ProductVariantRepository
) : IdDataLoader<ProductVariant, ProductVariantEntity>(repository)
