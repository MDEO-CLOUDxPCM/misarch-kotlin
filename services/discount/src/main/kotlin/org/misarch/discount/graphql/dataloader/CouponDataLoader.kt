package org.misarch.discount.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.discount.graphql.model.Coupon
import org.misarch.discount.persistence.model.CouponEntity
import org.misarch.discount.persistence.repository.CouponRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Coupon]s
 *
 * @param repository repository for [CouponEntity]s
 */
@Component
class CouponDataLoader(
    repository: CouponRepository
) : IdDataLoader<Coupon, CouponEntity>(repository)
