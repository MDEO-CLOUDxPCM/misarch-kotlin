package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.Product
import org.misarch.catalog.persistence.model.ProductEntity
import org.misarch.catalog.persistence.repository.ProductRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [Product]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class ProductDataLoader(
    repository: ProductRepository
) : IdDataLoader<Product, ProductEntity>(repository) {
}
