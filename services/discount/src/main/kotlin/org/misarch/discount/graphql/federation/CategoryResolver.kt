package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.discount.graphql.model.Category
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Category]s (no database access: the reference only carries the id).
 */
@Component
class CategoryResolver : ReferenceEntityResolver<Category>(Category::class, { Category(it) })
