package org.misarch.address.graphql.federation

import org.misarch.address.graphql.dataloader.AddressDataLoader
import org.misarch.address.graphql.model.UserAddress
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [UserAddress]es.
 */
@Component
class UserAddressResolver : DataLoaderEntityResolver<UserAddress>(UserAddress::class, AddressDataLoader::class)
