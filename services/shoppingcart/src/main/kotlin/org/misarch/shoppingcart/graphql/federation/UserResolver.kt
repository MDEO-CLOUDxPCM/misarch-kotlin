package org.misarch.shoppingcart.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.shoppingcart.graphql.dataloader.UserDataLoader
import org.misarch.shoppingcart.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (the user must be known to this service)
 */
@Component
class UserResolver : DataLoaderEntityResolver<User>(User::class, UserDataLoader::class)
