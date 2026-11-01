package org.misarch.`return`.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.`return`.graphql.dataloader.ReturnDataLoader
import org.misarch.`return`.graphql.model.Return
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Return]s (batched by [ReturnDataLoader]; an unknown id fails only its own entry).
 *
 * Fix (docs/fixes/return.md): the original registered no resolver although `Return` is a resolvable `@key`, so
 * every `_entities(Return)` lookup (e.g. shipment's `Shipment.return { reason }` through the gateway) failed.
 * Like `OrderItem.returnedWith`, the lookup needs no authorization.
 */
@Component
class ReturnResolver : DataLoaderEntityResolver<Return>(Return::class, ReturnDataLoader::class)
