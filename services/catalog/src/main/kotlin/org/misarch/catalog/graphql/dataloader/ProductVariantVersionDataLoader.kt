package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.ProductVariantVersion
import org.misarch.catalog.persistence.model.ProductVariantVersionEntity
import org.misarch.catalog.persistence.repository.ProductVariantVersionRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [ProductVariantVersion]s
 *
 * @param repository repository for [ProductVariantVersionEntity]s
 */
@Component
class ProductVariantVersionDataLoader(
    repository: ProductVariantVersionRepository
) : IdDataLoader<ProductVariantVersion, ProductVariantVersionEntity>(repository)
