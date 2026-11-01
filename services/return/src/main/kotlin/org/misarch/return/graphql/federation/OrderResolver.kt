package org.misarch.`return`.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.`return`.graphql.model.Order
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Order]s (no database access).
 */
@Component
class OrderResolver : ReferenceEntityResolver<Order>(Order::class, { Order(it) })
