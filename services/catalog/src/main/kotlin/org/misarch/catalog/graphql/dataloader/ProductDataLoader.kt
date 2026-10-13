package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.Product
import org.misarch.catalog.persistence.model.ProductEntity
import org.misarch.catalog.persistence.repository.ProductRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [Product]s
 *
 * @param repository repository for [ProductEntity]s
 */
@Component
class ProductDataLoader(
    repository: ProductRepository
) : IdDataLoader<Product, ProductEntity>(repository)
