package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.discount.graphql.model.Product
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Product]s (no database access: the reference only carries the id).
 */
@Component
class ProductResolver : ReferenceEntityResolver<Product>(Product::class, { Product(it) })
