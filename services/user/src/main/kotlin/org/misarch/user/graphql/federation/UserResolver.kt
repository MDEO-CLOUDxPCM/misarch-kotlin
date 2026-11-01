package org.misarch.user.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.user.graphql.dataloader.UserDataLoader
import org.misarch.user.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s.
 */
@Component
class UserResolver : DataLoaderEntityResolver<User>(User::class, UserDataLoader::class)
