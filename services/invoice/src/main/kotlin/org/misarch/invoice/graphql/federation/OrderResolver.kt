package org.misarch.invoice.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.invoice.graphql.model.Order
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Order]s.
 */
@Component
class OrderResolver : ReferenceEntityResolver<Order>(Order::class, ::Order)
