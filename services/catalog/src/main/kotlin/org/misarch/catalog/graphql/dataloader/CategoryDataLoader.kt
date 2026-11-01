package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.Category
import org.misarch.catalog.persistence.model.CategoryEntity
import org.misarch.catalog.persistence.repository.CategoryRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [Category]s
 *
 * @param repository repository for [CategoryEntity]s
 */
@Component
class CategoryDataLoader(
    repository: CategoryRepository
) : IdDataLoader<Category, CategoryEntity>(repository) {
    /** FIXED (docs/fixes/catalog.md): an unknown id fails only its own key (was: the whole batch, NPE). */
}
