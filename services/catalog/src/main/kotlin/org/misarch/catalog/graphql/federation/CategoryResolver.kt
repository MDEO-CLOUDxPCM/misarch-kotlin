package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.CategoryDataLoader
import org.misarch.catalog.graphql.model.Category
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Category]s.
 */
@Component
class CategoryResolver : DataLoaderEntityResolver<Category>(Category::class, CategoryDataLoader::class)
