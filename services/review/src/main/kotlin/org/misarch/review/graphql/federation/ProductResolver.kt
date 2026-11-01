package org.misarch.review.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.review.graphql.dataloader.ProductDataLoader
import org.misarch.review.graphql.model.Product
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Product]s (the id must be known to this service)
 */
@Component
class ProductResolver : DataLoaderEntityResolver<Product>(Product::class, ProductDataLoader::class)
