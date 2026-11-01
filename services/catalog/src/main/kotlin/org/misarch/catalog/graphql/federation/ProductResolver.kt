package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.ProductDataLoader
import org.misarch.catalog.graphql.model.Product
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Product]s.
 */
@Component
class ProductResolver : DataLoaderEntityResolver<Product>(Product::class, ProductDataLoader::class)
