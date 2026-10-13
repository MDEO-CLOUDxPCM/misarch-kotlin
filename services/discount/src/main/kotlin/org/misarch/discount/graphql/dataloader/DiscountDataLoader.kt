package org.misarch.discount.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.discount.graphql.model.Discount
import org.misarch.discount.persistence.model.DiscountEntity
import org.misarch.discount.persistence.repository.DiscountRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Discount]s
 *
 * @param repository repository for [DiscountEntity]s
 */
@Component
class DiscountDataLoader(
    repository: DiscountRepository
) : IdDataLoader<Discount, DiscountEntity>(repository)
