package org.misarch.`return`.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.`return`.graphql.dataloader.ReturnDataLoader
import org.misarch.`return`.graphql.model.Return
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Return]s (batched by [ReturnDataLoader]; an unknown id fails only its own entry).
 *
 * `Return` is a resolvable `@key`; the gateway resolves e.g. shipment's `Shipment.return { reason }` through it.
 * Like `OrderItem.returnedWith`, the lookup needs no authorization.
 */
@Component
class ReturnResolver : DataLoaderEntityResolver<Return>(Return::class, ReturnDataLoader::class)
