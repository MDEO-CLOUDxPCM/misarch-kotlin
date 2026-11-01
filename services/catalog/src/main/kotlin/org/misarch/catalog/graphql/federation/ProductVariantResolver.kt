package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.ProductVariantDataLoader
import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ProductVariant]s.
 */
@Component
class ProductVariantResolver : DataLoaderEntityResolver<ProductVariant>(ProductVariant::class, ProductVariantDataLoader::class)
