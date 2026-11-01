package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.discount.graphql.dataloader.DiscountUsageDataLoader
import org.misarch.discount.graphql.model.DiscountUsage
import org.springframework.stereotype.Component

/**
 * Federated resolver for [DiscountUsage]s.
 */
@Component
class DiscountUsageResolver : DataLoaderEntityResolver<DiscountUsage>(DiscountUsage::class, DiscountUsageDataLoader::class)
