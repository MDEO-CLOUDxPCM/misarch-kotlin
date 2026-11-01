package org.misarch.review.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.review.graphql.dataloader.ProductVariantDataLoader
import org.misarch.review.graphql.model.ProductVariant
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ProductVariant]s (the id must be known to this service)
 */
@Component
class ProductVariantResolver : DataLoaderEntityResolver<ProductVariant>(ProductVariant::class, ProductVariantDataLoader::class)
