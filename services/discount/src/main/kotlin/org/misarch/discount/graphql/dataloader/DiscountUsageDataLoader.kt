package org.misarch.discount.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.discount.graphql.model.DiscountUsage
import org.misarch.discount.persistence.model.DiscountUsageEntity
import org.misarch.discount.persistence.repository.DiscountUsageRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [DiscountUsage]s
 *
 * @param repository repository for [DiscountUsageEntity]s
 */
@Component
class DiscountUsageDataLoader(
    repository: DiscountUsageRepository
) : IdDataLoader<DiscountUsage, DiscountUsageEntity>(repository) {

    /** FIXED (docs/fixes/discount.md, Q4): an unknown id fails only its own key, not the whole batch. */
}
