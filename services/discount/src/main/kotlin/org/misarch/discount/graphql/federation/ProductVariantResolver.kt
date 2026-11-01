package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.discount.graphql.model.ProductVariant
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ProductVariant]s (no database access: the reference only carries the id).
 */
@Component
class ProductVariantResolver : ReferenceEntityResolver<ProductVariant>(ProductVariant::class, { ProductVariant(it) })
