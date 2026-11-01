package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.ProductVariantVersionDataLoader
import org.misarch.catalog.graphql.model.ProductVariantVersion
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ProductVariantVersion]s.
 */
@Component
class ProductVariantVersionResolver : DataLoaderEntityResolver<ProductVariantVersion>(ProductVariantVersion::class, ProductVariantVersionDataLoader::class)
