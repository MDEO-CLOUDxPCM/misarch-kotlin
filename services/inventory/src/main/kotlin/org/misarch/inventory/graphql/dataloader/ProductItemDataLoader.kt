package org.misarch.inventory.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.persistence.model.ProductItemEntity
import org.misarch.inventory.persistence.repository.ProductItemRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [ProductItem]s
 *
 * @param repository repository for [ProductItemEntity]s
 */
@Component
class ProductItemDataLoader(
    repository: ProductItemRepository
) : IdDataLoader<ProductItem, ProductItemEntity>(repository)
