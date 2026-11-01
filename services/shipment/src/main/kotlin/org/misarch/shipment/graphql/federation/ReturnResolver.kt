package org.misarch.shipment.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.shipment.graphql.model.Return
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Return]s (stub, no database access).
 */
@Component
class ReturnResolver : ReferenceEntityResolver<Return>(Return::class, { Return(it) })
