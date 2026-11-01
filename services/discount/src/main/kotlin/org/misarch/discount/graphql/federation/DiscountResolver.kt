package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.discount.graphql.dataloader.DiscountDataLoader
import org.misarch.discount.graphql.model.Discount
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Discount]s.
 */
@Component
class DiscountResolver : DataLoaderEntityResolver<Discount>(Discount::class, DiscountDataLoader::class)
