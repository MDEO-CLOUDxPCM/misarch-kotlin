package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.catalog.persistence.model.ProductVariantEntity
import org.misarch.catalog.persistence.repository.ProductVariantRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [ProductVariant]s. An unknown id fails only its own key, not the whole batch.
 *
 * @param repository repository for [ProductVariantEntity]s
 */
@Component
class ProductVariantDataLoader(
    repository: ProductVariantRepository
) : IdDataLoader<ProductVariant, ProductVariantEntity>(repository) {
}
