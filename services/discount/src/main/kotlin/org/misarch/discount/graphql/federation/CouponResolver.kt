package org.misarch.discount.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.discount.graphql.dataloader.CouponDataLoader
import org.misarch.discount.graphql.model.Coupon
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Coupon]s.
 */
@Component
class CouponResolver : DataLoaderEntityResolver<Coupon>(Coupon::class, CouponDataLoader::class)
