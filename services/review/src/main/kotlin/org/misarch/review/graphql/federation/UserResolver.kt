package org.misarch.review.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.review.graphql.dataloader.UserDataLoader
import org.misarch.review.graphql.model.User
import org.springframework.stereotype.Component

/**
 * Federated resolver for [User]s (the id must be known to this service)
 */
@Component
class UserResolver : DataLoaderEntityResolver<User>(User::class, UserDataLoader::class)
