package org.misarch.review.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.review.graphql.model.Product
import org.misarch.review.persistence.model.ProductEntity
import org.misarch.review.persistence.repository.ProductRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Product]s (replicated ids only)
 *
 * @param repository repository for [ProductEntity]s
 */
@Component
class ProductDataLoader(
    repository: ProductRepository
) : IdDataLoader<Product, ProductEntity>(repository)
