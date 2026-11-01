package org.misarch.inventory.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.inventory.graphql.model.ProductVariant
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ProductVariant]s (no database access: the fields are resolved from the id).
 */
@Component
class ProductVariantResolver : ReferenceEntityResolver<ProductVariant>(ProductVariant::class, ::ProductVariant)
